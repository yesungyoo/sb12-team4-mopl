package com.mopl.common.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 실제 구현은 S3ImageUploader(S3 설정 있을 때) 또는 NoOpImageUploader(설정 없을 때, 기본값)
 * 둘 중 하나가 조건부로 빈 등록된다. 호출부(UserService 등)는 어느 쪽이 활성화됐는지 신경 쓸 필요 없음.
 */
public interface ImageUploader {

    /** 업로드 후 접근 가능한 URL을 반환한다. */
    String upload(MultipartFile file);
}