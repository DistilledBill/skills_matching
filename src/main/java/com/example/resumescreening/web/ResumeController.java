package com.example.resumescreening.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.example.resumescreening.screening.ResumeContent;
import com.example.resumescreening.screening.ResumeFile;
import com.example.resumescreening.screening.ResumeFolder;
import com.example.resumescreening.screening.ResumeLoader;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The resume library under the resumes root. Text endpoints return contact details only when asked
 * ({@code redacted=false}); by default they return the text as Jev receives it.
 */
@RestController
@RequestMapping("/api")
public class ResumeController {

	private final ResumeLoader loader;

	public ResumeController(ResumeLoader loader) {
		this.loader = loader;
	}

	@GetMapping("/resume-folders")
	public List<ResumeFolder> folders() {
		return loader.listFolders();
	}

	@GetMapping("/resume-folders/{folder}")
	public List<ResumeFile> folder(@PathVariable String folder) {
		return loader.listFolder(folder);
	}

	@GetMapping("/resume-folders/{folder}/resumes/{fileName}")
	public ResumeContent resume(@PathVariable String folder, @PathVariable String fileName,
			@RequestParam(defaultValue = "true") boolean redacted) {
		ResumeContent content = loader.readOne(folder, fileName);
		return redacted ? content.redacted() : content;
	}

	/** The stored file, byte for byte, shown inline so a PDF opens in the browser. */
	@GetMapping("/resume-folders/{folder}/files/{fileName}")
	public ResponseEntity<Resource> file(@PathVariable String folder, @PathVariable String fileName) {
		Path path = loader.rawFile(folder, fileName);
		return ResponseEntity.ok()
			.contentType(mediaType(fileName))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(fileName).build().toString())
			.header("X-Content-Type-Options", "nosniff")
			.body(new FileSystemResource(path));
	}

	@PostMapping(path = "/resumes/text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public List<ResumeContent> uploadTexts(@RequestParam("files") List<MultipartFile> files,
			@RequestParam(defaultValue = "true") boolean redacted) {
		List<ResumeContent> contents = loader.uploadContents(files);
		return redacted ? contents.stream().map(ResumeContent::redacted).toList() : contents;
	}

	private static MediaType mediaType(String fileName) {
		return switch (String.valueOf(StringUtils.getFilenameExtension(fileName)).toLowerCase()) {
			case "pdf" -> MediaType.APPLICATION_PDF;
			case "md" -> new MediaType("text", "markdown", StandardCharsets.UTF_8);
			default -> new MediaType("text", "plain", StandardCharsets.UTF_8);
		};
	}

}
