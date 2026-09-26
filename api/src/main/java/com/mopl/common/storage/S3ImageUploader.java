package com.mopl.common.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * aws.s3.bucket 프로퍼티(=AWS_S3_BUCKET 환경변수)가 설정되어 있을 때만 이 빈이 등록된다.
 * 설정이 없으면(현재 상태) NoOpImageUploader 가 대신 등록되어, S3 인프라 없이도
 * 애플리케이션이 정상 기동/빌드/테스트된다.
 *
 * 버킷 생성, IAM 자격증명 발급은 AWS 콘솔에서 직접 처리해야 하는 부분이라 이 코드만으로는
 * 동작하지 않는다 - 인프라 담당자가 버킷/자격증명을 준비한 뒤 환경변수만 채우면 바로 동작한다.
 * 필요한 환경변수: AWS_S3_BUCKET, AWS_S3_REGION, (SDK 기본 자격증명 체인 사용 - 보통
 * AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 또는 IAM 역할)
 *
 * 리뷰 반영:
 * - MIME 타입/크기 검증은 이 클래스가 아니라 호출부(UserService.validateImage)에서 한다.
 *   업로드 방식(S3/NoOp)에 상관없이 동일한 검증 규칙이 적용되도록 하기 위함. 실제 파일 시그니처
 *   검사는 아직 없음(TODO, 후속 이슈).
 * - public URL 정책 확정: S3 객체 자체를 공개(퍼블릭)해서 S3 기본 URL을 그대로 사용하기로
 *   인프라 담당자와 합의됨. 그래서 aws.s3.public-base-url 을 별도로 설정하지 않으면(기본값),
 *   아래 s3Client.utilities().getUrl() 로 생성되는 URL을 그대로 쓰면 된다. CloudFront 등
 *   커스텀 도메인이 필요해지면 그때 public-base-url 값만 채우면 되도록 오버라이드 경로는 열어둠.
 *
 *   인프라 쪽에서 안내받은 설정 방법: Block Public Access 를 끄고, 버킷 정책에서
 *   Principal "*" 에게 s3:GetObject 권한만 허용(다른 권한은 열지 않음). 예시:
 *   {
 *     "Version": "2012-10-17",
 *     "Statement": [{
 *       "Effect": "Allow",
 *       "Principal": "*",
 *       "Action": "s3:GetObject",
 *       "Resource": "arn:aws:s3:::<버킷명>/*"
 *     }]
 *   }
 *   이 방식이면 객체 업로드 시 별도 ACL 지정이 필요 없다(버킷 정책이 우선 적용됨) -
 *   그래서 아래 putObject 호출에도 ACL 관련 옵션을 넣지 않았다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aws.s3", name = "bucket")
public class S3ImageUploader implements ImageUploader {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.public-base-url:}")
    private String publicBaseUrl;

    @Override
    public String upload(MultipartFile file) {
        String key = "profile-images/" + UUID.randomUUID() + "-" + sanitize(file.getOriginalFilename());

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        } catch (IOException e) {
            // TODO: 프로젝트의 공통 예외 체계(MoplException + ErrorCode)에 맞는 코드가 있으면
            // 그걸로 교체해주세요. CommonErrorCode 에 어떤 상수가 있는지 확인이 안 돼서
            // 일단 안전하게 UncheckedIOException 으로 감쌌습니다.
            throw new UncheckedIOException("이미지 업로드에 실패했습니다.", e);
        }

        // 커스텀 도메인/CDN 을 쓴다면 aws.s3.public-base-url 로 오버라이드, 없으면 S3 기본 URL 형식 사용
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return publicBaseUrl.replaceAll("/$", "") + "/" + key;
        }
        return s3Client.utilities().getUrl(b -> b.bucket(bucket).key(key)).toExternalForm();
    }

    private String sanitize(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "file";
        }
        return originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}