package com.example.resumescreening.web;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.job.JobSpecRepository;
import com.example.resumescreening.screening.CsvWriter;
import com.example.resumescreening.screening.ResumeDocument;
import com.example.resumescreening.screening.ResumeLoader;
import com.example.resumescreening.screening.ScreeningReport;
import com.example.resumescreening.screening.ScreeningService;
import com.example.resumescreening.typesafe.SystemOneRequest;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ScreeningController {

	private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

	private final JobSpecRepository jobs;

	private final ResumeLoader loader;

	private final ScreeningService screening;

	public ScreeningController(JobSpecRepository jobs, ResumeLoader loader, ScreeningService screening) {
		this.jobs = jobs;
		this.loader = loader;
		this.screening = screening;
	}

	@GetMapping("/jobs")
	public List<JobSpec> listJobs() {
		return jobs.all();
	}

	/** Screen uploaded resumes ({@code files} parts: .txt, .md, or .pdf). */
	@PostMapping(path = "/screenings/{jobId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<?> screenUploads(@PathVariable String jobId, @RequestParam("files") List<MultipartFile> files,
			@RequestParam(defaultValue = "json") String format) {
		JobSpec job = jobs.get(jobId);
		return respond(job, screening.screen(job, loader.fromUploads(files)), format);
	}

	/** Screen a folder inside the configured resumes directory; blank {@code path} means the directory itself. */
	@PostMapping("/screenings/{jobId}/folder")
	public ResponseEntity<?> screenFolder(@PathVariable String jobId, @RequestParam(defaultValue = "") String path,
			@RequestParam(defaultValue = "json") String format) {
		JobSpec job = jobs.get(jobId);
		return respond(job, screening.screen(job, loader.fromFolder(path)), format);
	}

	/** The TypeSafe request for the first resume, without calling the API. */
	@PostMapping("/screenings/{jobId}/preview")
	public SystemOneRequest preview(@PathVariable String jobId,
			@RequestParam(name = "files", required = false) List<MultipartFile> files,
			@RequestParam(required = false) String path) {
		JobSpec job = jobs.get(jobId);
		List<ResumeDocument> resumes = (files != null && !files.isEmpty()) ? loader.fromUploads(files)
				: loader.fromFolder(path);
		return screening.request(job, resumes.getFirst());
	}

	private static ResponseEntity<?> respond(JobSpec job, ScreeningReport report, String format) {
		if ("csv".equalsIgnoreCase(format)) {
			return ResponseEntity.ok()
				.contentType(TEXT_CSV)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(job.id() + ".csv").build().toString())
				.body(CsvWriter.write(job, report.candidates()));
		}
		return ResponseEntity.ok(report);
	}

}
