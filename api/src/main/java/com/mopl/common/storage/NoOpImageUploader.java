package com.mopl.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * S3ImageUploader 가 등록되지 않았을 때(=aws.s3.bucket 설정이 없을 때, 지금 이 프로젝트의 현재 상태)
 * 대신 등록되는 구현체. 실제로 업로드하지 않고 로그만 남기며, 항상 null 을 반환한다.
 * 호출부(UserService.updateProfile)는 profileImageUrl 이 null 이면 기존 값을 유지하도록
 * COALESCE 처리가 이미 되어 있어서, 이 상태에서도 "이름만 변경"은 정상 동작하고
 * "이미지 변경"만 조용히 무시되지 않고 아래처럼 로그로 남는다.
 *
 * ignored = NoOpImageUploader.class 가 필요한 이유: 이 클래스 자신이 ImageUploader 를 구현하다 보니,
 * @ConditionalOnMissingBean 이 후보 빈을 찾을 때 자기 자신의 빈 정의까지 "이미 존재하는 ImageUploader"로
 * 잘못 인식해서 등록을 건너뛰는 문제가 있었음(자기참조 오탐). ignored 로 자기 자신을 검색 대상에서
 * 제외해야 정상적으로 등록된다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = ImageUploader.class, ignored = NoOpImageUploader.class)
public class NoOpImageUploader implements ImageUploader {

    @Override
    public String upload(MultipartFile file) {
        log.warn(
                "[TODO: S3 인프라 미연동] 이미지 업로드 요청이 들어왔지만 실제로 업로드되지 않았습니다. "
                        + "originalFilename={}, size={}bytes",
                file.getOriginalFilename(), file.getSize()
        );
        return null;
    }
}