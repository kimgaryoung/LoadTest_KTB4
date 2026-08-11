const {
    createChatRoomAction,
    sendMultipleMessagesAction,
    addEmojiReactionAction,
} = require('../../actions/chat.actions');
const { expect } = require('@playwright/test');
const { randomUUID } = require('crypto');

const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const REACTION_BASELINE_MESSAGE_COUNT = parseInt(process.env.REACTION_BASELINE_MESSAGE_COUNT || '50', 10);
const REACTION_BASELINE_TOGGLE_COUNT = parseInt(process.env.REACTION_BASELINE_TOGGLE_COUNT || '20', 10);
const REACTION_BASELINE_EMOJI = process.env.REACTION_BASELINE_EMOJI || '😀';
const ACTION_TIMEOUT_SHORT = parseInt(process.env.ACTION_TIMEOUT_SHORT || '500', 10);
const ACTION_TIMEOUT_LONG = parseInt(process.env.ACTION_TIMEOUT_LONG || '2000', 10);

async function reactionBaselineScenario(page) {
    try {
        const roomName = `리액션_베이스라인_${randomUUID()}`;
        await createChatRoomAction(page, roomName);
        await expect(page).toHaveURL(new RegExp(`${BASE_URL}/chat/\\w+`));
        await expect(page.getByTestId('chat-message-input')).toBeVisible();

        console.log(`Preparing ${REACTION_BASELINE_MESSAGE_COUNT} messages for reaction baseline...`);
        await sendMultipleMessagesAction(page, REACTION_BASELINE_MESSAGE_COUNT);

        const reactionButton = page.getByTestId('message-reaction-button').last();
        await expect(reactionButton).toBeVisible({ timeout: ACTION_TIMEOUT_LONG });

        const durations = [];
        for (let i = 0; i < REACTION_BASELINE_TOGGLE_COUNT; i++) {
            const startedAt = Date.now();
            await addEmojiReactionAction(page, REACTION_BASELINE_EMOJI);
            await expect(page.getByTestId('emoji-picker-container')).not.toBeVisible({
                timeout: ACTION_TIMEOUT_LONG,
            });
            durations.push(Date.now() - startedAt);
            await page.waitForTimeout(ACTION_TIMEOUT_SHORT);
        }

        const totalMs = durations.reduce((sum, duration) => sum + duration, 0);
        const avgMs = totalMs / durations.length;
        const maxMs = Math.max(...durations);
        const minMs = Math.min(...durations);

        console.log(JSON.stringify({
            metric: 'reaction_baseline',
            roomUrl: page.url(),
            messageCount: REACTION_BASELINE_MESSAGE_COUNT,
            toggleCount: REACTION_BASELINE_TOGGLE_COUNT,
            emoji: REACTION_BASELINE_EMOJI,
            avgMs: Math.round(avgMs),
            minMs,
            maxMs,
        }));
    } catch (error) {
        console.error('Reaction baseline scenario failed:', error.message);
        throw error;
    }
}

module.exports = {
    reactionBaselineScenario,
};
