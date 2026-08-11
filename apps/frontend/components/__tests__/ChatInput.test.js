import React from 'react';
import { render, waitFor, fireEvent } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ChatInput from '../ChatInput';

describe('ChatInput', () => {
  it('renders the lazy emoji picker under React 19', async () => {
    const { container, getByLabelText } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    fireEvent.click(getByLabelText('이모티콘'));

    await waitFor(() => {
      expect(container.querySelector('em-emoji-picker')).toBeInTheDocument();
    });
  });

  it('does not send when Enter only completes an IME composition', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        onSubmit={onSubmit}
        room={{ participants: [] }}
      />
    );
    const input = getByTestId('chat-message-input');

    fireEvent.change(input, { target: { value: '채팅 치면 마지막 글자가 삽입' } });
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 });

    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.compositionEnd(input);
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({
      type: 'text',
      content: '채팅 치면 마지막 글자가 삽입',
    });
  });
});
