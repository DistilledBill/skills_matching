package com.example.resumescreening.web;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the web app from {@code static/}. Paths that are not a file, such as
 * {@code /jobs/x/results}, get {@code index.html} so the browser's router can
 * handle them on reload. {@code /api/...} is never rewritten, so an unknown
 * API path still returns 404.
 */
@Configuration
class SpaConfig implements WebMvcConfigurer {

	private static final Resource INDEX = new ClassPathResource("static/index.html");

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/**")
			.addResourceLocations("classpath:/static/")
			.resourceChain(true)
			.addResolver(new PathResourceResolver() {
				@Override
				protected Resource getResource(String path, Resource location) throws IOException {
					Resource file = location.createRelative(path);
					if (file.exists() && file.isReadable()) {
						return file;
					}
					boolean isApi = path.equals("api") || path.startsWith("api/");
					boolean looksLikeFile = path.substring(path.lastIndexOf('/') + 1).contains(".");
					return isApi || looksLikeFile || !INDEX.exists() ? null : INDEX;
				}
			});
	}

}
