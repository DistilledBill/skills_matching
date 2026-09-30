package com.example.resumescreening.web;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.resumescreening.job.JobSpec;
import com.example.resumescreening.job.JobSpecRepository;
import com.example.resumescreening.screening.CsvWriter;
import com.example.resumescreening.screening.InvalidResumeException;
import com.example.resumescreening.screening.ResumeDocument;
import com.example.resumescreening.screening.ResumeLoader;
import com.example.resumescreening.screening.ScreeningReport;
import com.example.resumescreening.screening.ScreeningService;
import com.example.resumescreening.typesafe.SystemOneRequest;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ScreeningController {

	static final String CACHED_HEADER = "X-Answer-Cached";

	private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

	private final JobSpecRepository jobs;

	private final ResumeLoader loader;

	private final ScreeningService screening;

	public ScreeningController(JobSpecRepository jobs, ResumeLoader loader, ScreeningService screening) {
		this.jobs = jobs;
		this.loader = loader;
		this.screening = screening;
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

	/**
	 * The TypeSafe request for one resume, without calling the API: the one named {@code name}, or the first.
	 * The {@value #CACHED_HEADER} header says whether screening it would be answered from the cache.
	 */
	@PostMapping("/screenings/{jobId}/preview")
	public ResponseEntity<SystemOneRequest> preview(@PathVariable String jobId,
			@RequestParam(name = "files", required = false) List<MultipartFile> files,
			@RequestParam(required = false) String path, @RequestParam(required = false) String name) {
		JobSpec job = jobs.get(jobId);
		return previewOf(job, pick(resumes(files, path), name));
	}

	/** Like {@link #preview}, for resume text that has not been saved to a file yet. */
	@PostMapping("/screenings/{jobId}/preview-text")
	public ResponseEntity<SystemOneRequest> previewText(@PathVariable String jobId, @RequestBody ResumeText body) {
		JobSpec job = jobs.get(jobId);
		if (body.text() == null || body.text().isBlank()) {
			throw new InvalidResumeException("Resume text is empty");
		}
		String name = body.name() == null || body.name().isBlank() ? "resume" : body.name();
		return previewOf(job, new ResumeDocument(name, body.text()));
	}

	/** How many of the given resumes already have a cached answer, so the UI can say how many API calls a run makes. */
	@PostMapping("/screenings/{jobId}/cache-status")
	public CacheStatus cacheStatus(@PathVariable String jobId,
			@RequestParam(name = "files", required = false) List<MultipartFile> files,
			@RequestParam(required = false) String path) {
		JobSpec job = jobs.get(jobId);
		List<ResumeDocument> resumes = resumes(files, path);
		int cached = (int) resumes.stream().filter(r -> screening.isCached(job, screening.request(job, r))).count();
		return new CacheStatus(resumes.size(), cached);
	}

	/** How many answers are cached for the job, from any folder, upload, or earlier version of its spec. */
	@GetMapping("/screenings/{jobId}/cache")
	public JobCache jobCache(@PathVariable String jobId) {
		return new JobCache(screening.cachedAnswers(jobs.get(jobId)));
	}

	/** Deletes every cached answer for the job. The next screening calls Jev again for each resume. */
	@DeleteMapping("/screenings/{jobId}/cache")
	public ClearedCache clearCache(@PathVariable String jobId) {
		return new ClearedCache(screening.clearCache(jobs.get(jobId)));
	}

	/** Resume text sent for preview before it is saved. */
	public record ResumeText(String name, String text) {
	}

	public record CacheStatus(int total, int cached) {
	}

	public record JobCache(int count) {
	}

	public record ClearedCache(int removed) {
	}

	private List<ResumeDocument> resumes(List<MultipartFile> files, String path) {
		return (files != null && !files.isEmpty()) ? loader.fromUploads(files) : loader.fromFolder(path);
	}

	private static ResumeDocument pick(List<ResumeDocument> resumes, String name) {
		if (name == null || name.isBlank()) {
			return resumes.getFirst();
		}
		return resumes.stream()
			.filter(r -> r.name().equals(name))
			.findFirst()
			.orElseThrow(() -> new InvalidResumeException("No resume named '" + name + "'"));
	}

	private ResponseEntity<SystemOneRequest> previewOf(JobSpec job, ResumeDocument resume) {
		SystemOneRequest request = screening.request(job, resume);
		return ResponseEntity.ok().header(CACHED_HEADER, String.valueOf(screening.isCached(job, request))).body(request);
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
