/**
 * Conversations & Direct Messages API Module
 *
 * Handles conversation and direct message operations:
 * - Conversation management
 * - Direct message listing
 * - Message read status
 */

import apiClient from './client';
import type {
  ConversationDto,
  ConversationCreateRequest,
  CursorResponseConversationDto,
  CursorResponseDirectMessageDto,
  FindConversationsParams,
  FindDmsParams,
} from '@/lib/types';

/**
 * Get conversations list with cursor pagination (대화 목록 조회)
 * GET /api/conversations
 *
 * @param params - Query parameters for sorting and pagination
 * @returns Paginated list of conversations
 *
 * Note: Only returns requester's conversations
 */
export const getConversations = async (
  params?: FindConversationsParams,
): Promise<CursorResponseConversationDto> => {
  const response = await apiClient.get<CursorResponseConversationDto>('/api/conversations', {
    params,
  });
  return response.data;
};

/**
 * Create conversation (대화 생성)
 * POST /api/conversations
 *
 * @param data - Conversation creation data (withUserId)
 * @returns Created conversation information
 */
export const createConversation = async (
  data: ConversationCreateRequest,
): Promise<ConversationDto> => {
  const response = await apiClient.post<ConversationDto>('/api/conversations', data);
  return response.data;
};

/**
 * Get direct messages in a conversation (DM 목록 조회)
 * GET /api/conversations/{conversationId}/direct-messages
 *
 * @param conversationId - Conversation ID
 * @param params - Query parameters for sorting and pagination
 * @returns Paginated list of direct messages
 *
 * Note: Requester must be a participant in the conversation
 */
export const getDirectMessages = async (
  conversationId: string,
  params?: FindDmsParams,
): Promise<CursorResponseDirectMessageDto> => {
  const response = await apiClient.get<CursorResponseDirectMessageDto>(
    `/api/conversations/${conversationId}/direct-messages`,
    { params },
  );
  return response.data;
};

/**
 * Mark direct message as read (DM 읽음 처리)
 * POST /api/conversations/{conversationId}/direct-messages/{directMessageId}/read
 *
 * @param conversationId - Conversation ID
 * @param directMessageId - Direct message ID to mark as read
 */
export const markDirectMessageAsRead = async (
  conversationId: string,
  directMessageId: string,
): Promise<void> => {
  await apiClient.post(
    `/api/conversations/${conversationId}/direct-messages/${directMessageId}/read`,
  );
};

/**
 * Get conversations with specific user (특정 사용자와의 대화 조회)
 * GET /api/conversations/with
 *
 * @param userId - Query parameters for specific user ID
 * @returns ConversationDto or 404 error if not found
 *
 * Note: Only returns requester's conversations
 */
export const getConversationWithUser = async (
    userId: string,
): Promise<ConversationDto> => {
  const response = await apiClient.get<ConversationDto>('/api/conversations/with', {
    params: {
      userId,
    },
  });
  return response.data;
};

/**
 * Get conversations with id (대화 조회)
 * GET /api/conversations/{conversationId}
 *
 * @param conversationId - Query parameters for specific conversation ID
 * @returns ConversationDto or 404 error if not found
 *
 * Note: Only returns requester's conversations
 */
export const getConversationById = async (
    conversationId: string,
): Promise<ConversationDto> => {
  const response = await apiClient.get<ConversationDto>(`/api/conversations/${conversationId}`);
  return response.data;
};
