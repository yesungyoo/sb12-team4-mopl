/**
 * Users API Module
 *
 * Handles user management operations:
 * - User registration (sign up)
 * - User list (admin only)
 * - Profile updates
 * - Role management (admin only)
 * - Password changes
 * - Account lock management (admin only)
 */

import apiClient from './client';
import type {
  UserDto,
  UserCreateRequest,
  UserUpdateRequest,
  UserRoleUpdateRequest,
  ChangePasswordRequest,
  UserLockUpdateRequest,
  CursorResponseUserDto,
  FindUsersParams,
} from '@/lib/types';

/**
 * Get user by ID (사용자 정보 조회)
 * GET /api/users/{userId}
 *
 * @param userId - User ID to retrieve
 * @returns User information
 */
export const getUserById = async (userId: string): Promise<UserDto> => {
  const response = await apiClient.get<UserDto>(`/api/users/${userId}`);
  return response.data;
};

/**
 * Get users list with cursor pagination (사용자 목록 조회)
 * GET /api/users
 *
 * @param params - Query parameters for filtering, sorting, and pagination
 * @returns Paginated list of users
 *
 * Note: Admin only
 */
export const getUsers = async (params?: FindUsersParams): Promise<CursorResponseUserDto> => {
  const response = await apiClient.get<CursorResponseUserDto>('/api/users', { params });
  return response.data;
};

/**
 * Create user (회원가입)
 * POST /api/users
 *
 * @param data - User registration data
 * @returns Created user information
 */
export const createUser = async (data: UserCreateRequest): Promise<UserDto> => {
  const response = await apiClient.post<UserDto>('/api/users', data);
  return response.data;
};

/**
 * Update user profile (프로필 변경)
 * PATCH /api/users/{userId}
 *
 * @param userId - User ID to update
 * @param data - Profile data to update
 * @param image - Optional profile image file
 * @returns Updated user information
 *
 * Note: Users can only update their own profile
 */
export const updateUser = async (
  userId: string,
  data: UserUpdateRequest,
  image?: File,
): Promise<UserDto> => {
  const formData = new FormData();

  // Append request data as JSON blob
  formData.append(
    'request',
    new Blob([JSON.stringify(data)], { type: 'application/json' }),
  );

  // Append image if provided
  if (image) {
    formData.append('image', image);
  }

  const response = await apiClient.patch<UserDto>(`/api/users/${userId}`, formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
    },
  });

  return response.data;
};

/**
 * Update user role (권한 수정)
 * PATCH /api/users/{userId}/role
 *
 * @param userId - User ID to update
 * @param data - Role update data
 *
 * Note: Admin only
 */
export const updateUserRole = async (
  userId: string,
  data: UserRoleUpdateRequest,
): Promise<void> => {
  await apiClient.patch(`/api/users/${userId}/role`, data);
};

/**
 * Update user password (비밀번호 변경)
 * PATCH /api/users/{userId}/password
 *
 * @param userId - User ID to update
 * @param data - Password change data
 *
 * Note: Users can only update their own password
 */
export const updateUserPassword = async (
  userId: string,
  data: ChangePasswordRequest,
): Promise<void> => {
  await apiClient.patch(`/api/users/${userId}/password`, data);
};

/**
 * Update user lock status (계정 잠금 상태 변경)
 * PATCH /api/users/{userId}/locked
 *
 * @param userId - User ID to update
 * @param data - Lock status update data
 *
 * Note: Admin only
 */
export const updateUserLocked = async (
  userId: string,
  data: UserLockUpdateRequest,
): Promise<void> => {
  await apiClient.patch(`/api/users/${userId}/locked`, data);
};
