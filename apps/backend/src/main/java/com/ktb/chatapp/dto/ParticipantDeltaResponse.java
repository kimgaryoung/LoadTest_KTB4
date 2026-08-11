package com.ktb.chatapp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 참가자 목록에 적용할 단일 변경사항.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParticipantDeltaResponse {
    private String roomId;
    private String type;
    private UserResponse participant;
}
