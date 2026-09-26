package com.mopl.common.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3ImageUploader 와 마찬가지로 aws.s3.bucket 이 설정되어 있을 때만 S3Client 빈을 만든다.
 * 자격증명은 SDK 기본 자격증명 체인(DefaultCredentialsProvider)을 그대로 사용 -
 * 환경변수(AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY) 또는 IAM 역할 등 인프라 담당자가
 * 어떤 방식으로 자격증명을 주더라도 별도 코드 수정 없이 동작한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "aws.s3", name = "bucket")
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(org.springframework.core.env.Environment env) {
        String region = env.getProperty("aws.s3.region", "ap-northeast-2");
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}