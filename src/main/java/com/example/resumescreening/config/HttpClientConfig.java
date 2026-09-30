package com.example.resumescreening.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

	@Bean
	RestClient.Builder typeSafeRestClientBuilder(TypeSafeProperties props) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(props.timeout());
		return RestClient.builder().requestFactory(factory);
	}

	@Bean
	RestClient.Builder anthropicRestClientBuilder(AnthropicProperties props) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(props.timeout());
		return RestClient.builder().requestFactory(factory);
	}

}
