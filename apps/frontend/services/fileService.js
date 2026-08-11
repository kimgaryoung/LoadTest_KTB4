import axios, { isCancel, CancelToken } from 'axios';
import axiosInstance from './axios';
import { Toast } from '../components/Toast';

class FileService {
  constructor() {
    this.baseUrl = process.env.NEXT_PUBLIC_API_URL;
    this.uploadLimit = 5 * 1024 * 1024; // 백엔드 공통 제한과 동일하게 유지
    this.retryAttempts = 3;
    this.retryDelay = 1000;
    this.activeUploads = new Map();

    this.allowedTypes = {
      image: {
        extensions: ['.jpg', '.jpeg', '.png', '.gif', '.webp'],
        mimeTypes: ['image/jpeg', 'image/png', 'image/gif', 'image/webp'],
        maxSize: 5 * 1024 * 1024,
        name: '이미지'
      },
      document: {
        extensions: ['.pdf'],
        mimeTypes: ['application/pdf'],
        maxSize: 5 * 1024 * 1024,
        name: 'PDF 문서'
      }
    };
  }

  async validateFile(file) {
    if (!file) {
      const message = '파일이 선택되지 않았습니다.';
      Toast.error(message);
      return { success: false, message };
    }

    if (file.size > this.uploadLimit) {
      const message = `파일 크기는 ${this.formatFileSize(this.uploadLimit)}를 초과할 수 없습니다.`;
      Toast.error(message);
      return { success: false, message };
    }

    let isAllowedType = false;
    let maxTypeSize = 0;
    let typeConfig = null;

    for (const config of Object.values(this.allowedTypes)) {
      if (config.mimeTypes.includes(file.type)) {
        isAllowedType = true;
        maxTypeSize = config.maxSize;
        typeConfig = config;
        break;
      }
    }

    if (!isAllowedType) {
      const message = '지원하지 않는 파일 형식입니다.';
      Toast.error(message);
      return { success: false, message };
    }

    if (file.size > maxTypeSize) {
      const message = `${typeConfig.name} 파일은 ${this.formatFileSize(maxTypeSize)}를 초과할 수 없습니다.`;
      Toast.error(message);
      return { success: false, message };
    }

    const ext = this.getFileExtension(file.name);
    if (!typeConfig.extensions.includes(ext.toLowerCase())) {
      const message = '파일 확장자가 올바르지 않습니다.';
      Toast.error(message);
      return { success: false, message };
    }

    return { success: true };
  }

  async uploadFile(file, onProgress, token, sessionId) {
    const validationResult = await this.validateFile(file);
    if (!validationResult.success) {
      return validationResult;
    }

    if (process.env.NEXT_PUBLIC_FILE_DIRECT_UPLOAD_ENABLED === 'true') {
      return this.uploadFileDirect(file, onProgress);
    }

    try {
      const formData = new FormData();
      formData.append('file', file);

      const source = CancelToken.source();
      this.activeUploads.set(file.name, source);

      const uploadUrl = this.baseUrl ?
        `${this.baseUrl}/api/files/upload` :
        '/api/files/upload';

      // token과 sessionId는 axios 인터셉터에서 자동으로 추가되므로
      // 여기서는 명시적으로 전달하지 않아도 됩니다
      const response = await axiosInstance.post(uploadUrl, formData, {
        headers: {
          'Content-Type': 'multipart/form-data'
        },
        // 파일 전송은 공통 API 타임아웃보다 길 수 있어 업로드용 타임아웃을 사용한다.
        timeout: 30000,
        cancelToken: source.token,
        withCredentials: true,
        onUploadProgress: (progressEvent) => {
          if (onProgress) {
            const percentCompleted = Math.round(
              (progressEvent.loaded * 100) / progressEvent.total
            );
            onProgress(percentCompleted);
          }
        }
      });

      this.activeUploads.delete(file.name);

      if (!response.data || !response.data.success) {
        return {
          success: false,
          message: response.data?.message || '파일 업로드에 실패했습니다.'
        };
      }

      const fileData = response.data.file;
      return {
        success: true,
        data: {
          ...response.data,
          file: {
            ...fileData,
            url: this.getFileUrl(fileData.filename, true)
          }
        }
      };

    } catch (error) {
      this.activeUploads.delete(file.name);

      if (isCancel(error)) {
        return {
          success: false,
          message: '업로드가 취소되었습니다.'
        };
      }

      if (error.response?.status === 401) {
        throw new Error('Authentication expired. Please login again.');
      }

      return this.handleUploadError(error);
    }
  }

  async uploadFileDirect(file, onProgress) {
    const source = CancelToken.source();
    this.activeUploads.set(file.name, source);

    try {
      const checksumSha256 = await this.sha256Base64(file);
      const prepared = await axiosInstance.post('/api/files/presign', {
        originalFilename: file.name,
        contentType: file.type,
        size: file.size,
        checksumSha256,
      }, {
        retry: false,
        cancelToken: source.token,
      });

      await this.putToObjectStorage(prepared.data, file, source.token, onProgress);

      const completed = await axiosInstance.post('/api/files/upload', {
        uploadIntentId: prepared.data.uploadIntentId,
      }, {
        retry: false,
        cancelToken: source.token,
      });
      this.activeUploads.delete(file.name);

      const fileData = completed.data.file;
      return {
        success: true,
        data: {
          ...completed.data,
          file: {
            ...fileData,
            url: this.getFileUrl(fileData.filename, true),
          },
        },
      };
    } catch (error) {
      this.activeUploads.delete(file.name);
      if (isCancel(error)) {
        return { success: false, message: '업로드가 취소되었습니다.' };
      }
      return this.handleUploadError(error);
    }
  }

  async uploadProfileImageDirect(file, onProgress) {
    const checksumSha256 = await this.sha256Base64(file);
    const prepared = await axiosInstance.post('/api/users/presign-profile-image', {
      originalFilename: file.name,
      contentType: file.type,
      size: file.size,
      checksumSha256,
    }, { retry: false });

    await this.putToObjectStorage(prepared.data, file, undefined, onProgress);
    const completed = await axiosInstance.post('/api/users/profile-image', {
      uploadIntentId: prepared.data.uploadIntentId,
    }, { retry: false });
    return completed.data;
  }

  async putToObjectStorage(prepared, file, cancelToken, onProgress) {
    const headers = {};
    for (const [name, values] of Object.entries(prepared.headers || {})) {
      const lowerName = name.toLowerCase();
      if (lowerName === 'host' || lowerName === 'content-length') continue;
      headers[name] = Array.isArray(values) ? values.join(',') : values;
    }
    await axios.put(prepared.uploadUrl, file, {
      headers,
      timeout: 30000,
      cancelToken,
      withCredentials: false,
      onUploadProgress: (progressEvent) => {
        if (onProgress && progressEvent.total) {
          onProgress(Math.round((progressEvent.loaded * 100) / progressEvent.total));
        }
      },
    });
  }

  async sha256Base64(file) {
    const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer());
    const bytes = new Uint8Array(digest);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return globalThis.btoa(binary);
  }
  getFileUrl(filename, forPreview = false) {
    if (!filename) return '';

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = forPreview ? 'view' : 'download';
    return `${baseUrl}/api/files/${endpoint}/${filename}`;
  }

  getPreviewUrl(file, token, sessionId, withAuth = true) {
    if (!file?.filename) return '';

    const baseUrl = `${process.env.NEXT_PUBLIC_API_URL}/api/files/view/${file.filename}`;

    if (!withAuth) return baseUrl;

    if (!token || !sessionId) return baseUrl;

    // URL 객체 생성 전 프로토콜 확인
    const url = new URL(baseUrl);
    url.searchParams.append('token', encodeURIComponent(token));
    url.searchParams.append('sessionId', encodeURIComponent(sessionId));

    return url.toString();
  }

  getFileExtension(filename) {
    if (!filename) return '';
    const parts = filename.split('.');
    return parts.length > 1 ? `.${parts.pop().toLowerCase()}` : '';
  }

  formatFileSize(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return `${parseFloat((bytes / Math.pow(1024, i)).toFixed(2))} ${units[i]}`;
  }

  handleUploadError(error) {
    if (error.code === 'ECONNABORTED') {
      return {
        success: false,
        message: '파일 업로드 시간이 초과되었습니다.'
      };
    }

    const status = error.response?.status ?? error.status;
    const message = error.response?.data?.message ?? error.message;

    switch (status) {
      case 400:
        return {
          success: false,
          message: message || '잘못된 요청입니다.'
        };
      case 401:
        return {
          success: false,
          message: '인증이 필요합니다.'
        };
      case 413:
        return {
          success: false,
          message: message || '파일이 너무 큽니다.'
        };
      case 415:
        return {
          success: false,
          message: '지원하지 않는 파일 형식입니다.'
        };
      default:
        break;
    }

    console.error('Upload error:', error);

    if (axios.isAxiosError(error)) {
      switch (status) {
        case 500:
          return {
            success: false,
            message: '서버 오류가 발생했습니다.'
          };
        default:
          return {
            success: false,
            message: message || '파일 업로드에 실패했습니다.'
          };
      }
    }

    return {
      success: false,
      message: error.message || '알 수 없는 오류가 발생했습니다.',
      error
    };
  }

  cancelUpload(filename) {
    const source = this.activeUploads.get(filename);
    if (source) {
      source.cancel('Upload canceled by user');
      this.activeUploads.delete(filename);
      return {
        success: true,
        message: '업로드가 취소되었습니다.'
      };
    }
    return {
      success: false,
      message: '취소할 업로드를 찾을 수 없습니다.'
    };
  }

}

export default new FileService();
