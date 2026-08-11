import { beforeEach, describe, expect, it, vi } from 'vitest';

const { axiosPut, apiPost } = vi.hoisted(() => ({
  axiosPut: vi.fn(),
  apiPost: vi.fn(),
}));

vi.mock('axios', () => ({
  default: {
    put: axiosPut,
    isAxiosError: vi.fn(() => true),
  },
  isCancel: vi.fn(() => false),
  CancelToken: {
    source: () => ({ token: 'cancel-token', cancel: vi.fn() }),
  },
}));

vi.mock('../axios', () => ({
  default: { post: apiPost },
}));

vi.mock('../../components/Toast', () => ({
  Toast: { error: vi.fn() },
}));

import fileService from '../fileService';

describe('fileService direct upload', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(fileService, 'sha256Base64').mockResolvedValue('checksum-base64');
  });

  it('uploads profile bytes to S3 and completes through the existing profile API', async () => {
    apiPost
      .mockResolvedValueOnce({
        data: {
          uploadIntentId: 'intent-1',
          uploadUrl: 'https://bucket.example/upload',
          headers: {
            Host: ['bucket.example'],
            'Content-Type': ['image/png'],
            'x-amz-checksum-sha256': ['checksum-base64'],
          },
        },
      })
      .mockResolvedValueOnce({
        data: { success: true, imageUrl: '/api/files/profiles/generated.png' },
      });
    axiosPut.mockResolvedValue({ status: 200 });
    const file = { name: 'profile.png', type: 'image/png', size: 100 };

    const result = await fileService.uploadProfileImageDirect(file);

    expect(apiPost).toHaveBeenNthCalledWith(
      1,
      '/api/users/presign-profile-image',
      expect.objectContaining({ checksumSha256: 'checksum-base64' }),
      { retry: false }
    );
    expect(axiosPut).toHaveBeenCalledWith(
      'https://bucket.example/upload',
      file,
      expect.objectContaining({
        withCredentials: false,
        headers: {
          'Content-Type': 'image/png',
          'x-amz-checksum-sha256': 'checksum-base64',
        },
      })
    );
    expect(apiPost).toHaveBeenNthCalledWith(
      2,
      '/api/users/profile-image',
      { uploadIntentId: 'intent-1' },
      { retry: false }
    );
    expect(result.imageUrl).toBe('/api/files/profiles/generated.png');
  });
});
