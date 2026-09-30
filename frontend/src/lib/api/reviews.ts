/**
 * Reviews API Module
 *
 * Handles review management operations:
 * - Review listing and filtering
 * - Review CRUD operations
 */

import apiClient from './client';
import type {
  ReviewDto,
  ReviewCreateRequest,
  ReviewUpdateRequest,
  CursorResponseReviewDto,
  FindReviewsParams,
} from '@/lib/types';

/**
 * Get reviews list with cursor pagination (리뷰 목록 조회)
 * GET /api/reviews
 *
 * @param params - Query parameters for filtering, sorting, and pagination
 * @returns Paginated list of reviews
 */
export const getReviews = async (params?: FindReviewsParams): Promise<CursorResponseReviewDto> => {
  const response = await apiClient.get<CursorResponseReviewDto>('/api/reviews', { params });
  return response.data;
};

/**
 * Create review (리뷰 생성)
 * POST /api/reviews
 *
 * @param data - Review data
 * @returns Created review information
 *
 * Note: Created review is owned by the API requester
 */
export const createReview = async (data: ReviewCreateRequest): Promise<ReviewDto> => {
  const response = await apiClient.post<ReviewDto>('/api/reviews', data);
  return response.data;
};

/**
 * Update review (리뷰 수정)
 * PATCH /api/reviews/{reviewId}
 *
 * @param reviewId - Review ID to update
 * @param data - Review data to update
 * @returns Updated review information
 *
 * Note: Only review author can update
 */
export const updateReview = async (
  reviewId: string,
  data: ReviewUpdateRequest,
): Promise<ReviewDto> => {
  const response = await apiClient.patch<ReviewDto>(`/api/reviews/${reviewId}`, data);
  return response.data;
};

/**
 * Delete review (리뷰 삭제)
 * DELETE /api/reviews/{reviewId}
 *
 * @param reviewId - Review ID to delete
 *
 * Note: Only review author can delete
 */
export const deleteReview = async (reviewId: string): Promise<void> => {
  await apiClient.delete(`/api/reviews/${reviewId}`);
};
