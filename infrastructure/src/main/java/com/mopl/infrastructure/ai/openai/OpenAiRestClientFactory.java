package com.mopl.infrastructure.ai.openai;

import com.mopl.infrastructure.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class OpenAiRestClientFactory {

    private final RestClient.Builder restClientBuilder;
    private final AiProperties aiProperties;

    public RestClient create() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout((aiProperties.connectTimeout()));
        requestFactory.setReadTimeout(aiProperties.readTimeout());

        return restClientBuilder.clone()
                .baseUrl(aiProperties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
