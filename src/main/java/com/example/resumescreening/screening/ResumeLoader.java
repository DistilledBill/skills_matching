package com.example.resumescreening.screening;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import com.example.resumescreening.config.ScreeningProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** Reads .txt, .md, and .pdf resumes from uploads or from a folder under the resumes root. */
@Component
public class ResumeLoader {

	static final Set<String> SUPPORTED = Set.of("txt", "md", "pdf");

	private final Path root;

	public ResumeLoader(ScreeningProperties props) {
		this.root = props.resumesDir();
	}

	public List<ResumeDocument> fromUploads(List<MultipartFile> files) {
		List<ResumeDocument> resumes = new ArrayList<>();
		for (MultipartFile file : files == null ? List.<MultipartFile>of() : files) {
			String filename = StringUtils.getFilename(StringUtils.cleanPath(String.valueOf(file.getOriginalFilename())));
			if (!isSupported(filename)) {
				throw new InvalidResumeException("Unsupported file '" + filename + "'; expected " + SUPPORTED);
			}
			try {
				resumes.add(load(filename, file.getBytes()));
			}
			catch (IOException ex) {
				throw new InvalidResumeException("Could not read upload '" + filename + "'", ex);
			}
		}
		return requireAny(resumes, "No resumes uploaded; send one or more 'files' parts");
	}

	/**
	 * @param relativePath folder relative to the resumes root; blank means the root itself.
	 * Paths that resolve outside the root (via "..", absolute paths, or symlinks) are rejected.
	 */
	public List<ResumeDocument> fromFolder(String relativePath) {
		Path folder = resolveInsideRoot(relativePath == null ? "" : relativePath);
		try (Stream<Path> entries = Files.list(folder)) {
			List<Path> paths = entries.filter(Files::isRegularFile)
				.filter(p -> isSupported(p.getFileName().toString()))
				.sorted()
				.toList();
			List<ResumeDocument> resumes = new ArrayList<>();
			for (Path path : paths) {
				resumes.add(load(path.getFileName().toString(), Files.readAllBytes(path)));
			}
			return requireAny(resumes, "No " + SUPPORTED + " resumes found in '" + relativePath + "'");
		}
		catch (IOException ex) {
			throw new InvalidResumeException("Could not read folder '" + relativePath + "'", ex);
		}
	}

	/** Folders directly under the resumes root, by name. Folder screening does not recurse, so deeper ones are not listed. */
	public List<ResumeFolder> listFolders() {
		Path realRoot = resolveInsideRoot("");
		try (Stream<Path> entries = Files.list(realRoot)) {
			List<ResumeFolder> folders = new ArrayList<>();
			for (Path dir : entries.filter(Files::isDirectory)
				.filter(p -> !p.getFileName().toString().startsWith("."))
				.sorted()
				.toList()) {
				folders.add(new ResumeFolder(dir.getFileName().toString(), countResumes(dir)));
			}
			return folders;
		}
		catch (IOException ex) {
			throw new InvalidResumeException("Could not list folders in " + root, ex);
		}
	}

	/** The supported resume files directly inside one folder under the resumes root, by file name. */
	public List<ResumeFile> listFolder(String relativePath) {
		Path folder = resolveInsideRoot(relativePath == null ? "" : relativePath);
		try (Stream<Path> entries = Files.list(folder)) {
			List<ResumeFile> files = new ArrayList<>();
			for (Path path : entries.filter(Files::isRegularFile)
				.filter(p -> isSupported(p.getFileName().toString()))
				.sorted()
				.toList()) {
				String fileName = path.getFileName().toString();
				files.add(new ResumeFile(fileName, StringUtils.stripFilenameExtension(fileName), extension(fileName),
						Files.size(path)));
			}
			return files;
		}
		catch (IOException ex) {
			throw new InvalidResumeException("Could not read folder '" + relativePath + "'", ex);
		}
	}

	private static int countResumes(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return (int) files.filter(Files::isRegularFile).filter(p -> isSupported(p.getFileName().toString())).count();
		}
	}

	ResumeDocument load(String filename, byte[] bytes) {
		String name = StringUtils.stripFilenameExtension(filename);
		if ("pdf".equals(extension(filename))) {
			try (PDDocument pdf = Loader.loadPDF(bytes)) {
				return new ResumeDocument(name, new PDFTextStripper().getText(pdf));
			}
			catch (IOException ex) {
				throw new InvalidResumeException("Could not read PDF '" + filename + "'", ex);
			}
		}
		return new ResumeDocument(name, new String(bytes, StandardCharsets.UTF_8));
	}

	private Path resolveInsideRoot(String relativePath) {
		try {
			Path realRoot = root.toRealPath();
			Path candidate = realRoot.resolve(relativePath).normalize();
			if (!candidate.startsWith(realRoot) || !Files.isDirectory(candidate)
					|| !candidate.toRealPath().startsWith(realRoot)) {
				throw new InvalidResumeException("'" + relativePath + "' is not a folder inside " + root);
			}
			return candidate;
		}
		catch (IOException ex) {
			throw new InvalidResumeException("'" + relativePath + "' is not a folder inside " + root, ex);
		}
	}

	private static boolean isSupported(String filename) {
		return filename != null && SUPPORTED.contains(extension(filename));
	}

	private static String extension(String filename) {
		String ext = StringUtils.getFilenameExtension(filename);
		return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
	}

	private static List<ResumeDocument> requireAny(List<ResumeDocument> resumes, String message) {
		if (resumes.isEmpty()) {
			throw new InvalidResumeException(message);
		}
		return resumes;
	}

}
