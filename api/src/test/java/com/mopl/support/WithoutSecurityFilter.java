package com.mopl.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * @WebMvcTest(Controller slice test)에서 Spring Security 필터체인을 완전히 끄기 위한 메타 애너테이션.
 *
 * 사용법 (ContentControllerTest 예시):
 *
 *   @WebMvcTest(ContentController.class)
 *   @WithoutSecurityFilter
 *   class ContentControllerTest { ... }
 *
 * addFilters = false 로 MockMvc 요청이 Security 필터(및 그 안의 JwtAuthenticationFilter)를
 * 아예 거치지 않게 만들어서, 인증/인가와 무관한 컨트롤러 테스트가 Security 설정 때문에
 * 깨지는 것을 막는다. 인증/인가 자체를 검증해야 하는 테스트(UserControllerTest의 어드민
 * 권한 체크 등)에는 이 애너테이션을 쓰면 안 되고, 대신 spring-security-test 의
 * @WithMockUser 또는 SecurityMockMvcRequestPostProcessors 를 사용해야 한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@AutoConfigureMockMvc(addFilters = false)
public @interface WithoutSecurityFilter {
}