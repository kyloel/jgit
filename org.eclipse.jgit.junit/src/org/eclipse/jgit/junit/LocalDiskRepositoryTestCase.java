/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2007, Shawn O. Pearce <spearce@spearce.org>
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.junit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.eclipse.jgit.lib.FileBasedConfig;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.WindowCache;
import org.eclipse.jgit.lib.WindowCacheConfig;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.SystemReader;

/**
 * JUnit TestCase with specialized support for temporary local repository.
 * <p>
 * A temporary directory is created for each test, allowing each test to use a
 * fresh environment. The temporary directory is cleaned up after the test ends.
 * <p>
 * Callers should not use {@link RepositoryCache} from within these tests as it
 * may wedge file descriptors open past the end of the test.
 * <p>
 * A system property {@code jgit.junit.usemmap} defines whether memory mapping
 * is used. Memory mapping has an effect on the file system, in that memory
 * mapped files in Java cannot be deleted as long as the mapped arrays have not
 * been reclaimed by the garbage collector. The programmer cannot control this
 * with precision, so temporary files may hang around longer than desired during
 * a test, or tests may fail altogether if there is insufficient file
 * descriptors or address space for the test process.
 */
public abstract class LocalDiskRepositoryTestCase extends TestCase {
	private static Thread shutdownHook;

	private static int testCount;

	private static final boolean useMMAP = "true".equals(System
			.getProperty("jgit.junit.usemmap"));

	/** A fake (but stable) identity for author fields in the test. */
	protected PersonIdent author;

	/** A fake (but stable) identity for committer fields in the test. */
	protected PersonIdent committer;

	private final File trash = new File(new File("target"), "trash");

	private final List<Repository> toClose = new ArrayList<Repository>();

	private MockSystemReader mockSystemReader;

	@Override
	protected void setUp() throws Exception {
		super.setUp();

		if (shutdownHook == null) {
			shutdownHook = new Thread() {
				@Override
				public void run() {
					System.gc();
					recursiveDelete("SHUTDOWN", trash, false, false);
				}
			};
			Runtime.getRuntime().addShutdownHook(shutdownHook);
		}

		recursiveDelete(testName(), trash, true, false);

		mockSystemReader = new MockSystemReader();
		mockSystemReader.userGitConfig = new FileBasedConfig(new File(trash,
				"usergitconfig"));
		SystemReader.setInstance(mockSystemReader);

		final long now = mockSystemReader.getCurrentTime();
		final int tz = mockSystemReader.getTimezone(now);
		author = new PersonIdent("J. Author", "jauthor@example.com");
		author = new PersonIdent(author, now, tz);

		committer = new PersonIdent("J. Committer", "jcommitter@example.com");
		committer = new PersonIdent(committer, now, tz);

		final WindowCacheConfig c = new WindowCacheConfig();
		c.setPackedGitLimit(128 * WindowCacheConfig.KB);
		c.setPackedGitWindowSize(8 * WindowCacheConfig.KB);
		c.setPackedGitMMAP(useMMAP);
		c.setDeltaBaseCacheLimit(8 * WindowCacheConfig.KB);
		WindowCache.reconfigure(c);
	}

	@Override
	protected void tearDown() throws Exception {
		RepositoryCache.clear();
		for (Repository r : toClose)
			r.close();
		toClose.clear();

		// Since memory mapping is controlled by the GC we need to
		// tell it this is a good time to clean up and unlock
		// memory mapped files.
		//
		if (useMMAP)
			System.gc();

		recursiveDelete(testName(), trash, false, true);
		super.tearDown();
	}

	/** Increment the {@link #author} and {@link #committer} times. */
	protected void tick() {
		final long delta = TimeUnit.MILLISECONDS.convert(5 * 60,
				TimeUnit.SECONDS);
		final long now = author.getWhen().getTime() + delta;
		final int tz = mockSystemReader.getTimezone(now);

		author = new PersonIdent(author, now, tz);
		committer = new PersonIdent(committer, now, tz);
	}

	/**
	 * Recursively delete a directory, failing the test if the delete fails.
	 *
	 * @param dir
	 *            the recursively directory to delete, if present.
	 */
	protected void recursiveDelete(final File dir) {
		recursiveDelete(testName(), dir, false, true);
	}

	private static boolean recursiveDelete(final String testName,
			final File dir, boolean silent, boolean failOnError) {
		assert !(silent && failOnError);
		if (!dir.exists()) {
			return silent;
		}
		final File[] ls = dir.listFiles();
		if (ls != null) {
			for (int k = 0; k < ls.length; k++) {
				final File e = ls[k];
				if (e.isDirectory()) {
					silent = recursiveDelete(testName, e, silent, failOnError);
				} else {
					if (!e.delete()) {
						if (!silent) {
							reportDeleteFailure(testName, failOnError, e);
						}
						silent = !failOnError;
					}
				}
			}
		}
		if (!dir.delete()) {
			if (!silent) {
				reportDeleteFailure(testName, failOnError, dir);
			}
			silent = !failOnError;
		}
		return silent;
	}

	private static void reportDeleteFailure(final String testName,
			final boolean failOnError, final File e) {
		final String severity;
		if (failOnError)
			severity = "ERROR";
		else
			severity = "WARNING";

		final String msg = severity + ": Failed to delete " + e + " in "
				+ testName;
		if (failOnError)
			fail(msg);
		else
			System.err.println(msg);
	}

	/**
	 * Creates a new empty bare repository.
	 *
	 * @return the newly created repository, opened for access
	 * @throws IOException
	 *             the repository could not be created in the temporary area
	 */
	protected Repository createBareRepository() throws IOException {
		return createRepository(true /* bare */);
	}

	/**
	 * Creates a new empty repository within a new empty working directory.
	 *
	 * @return the newly created repository, opened for access
	 * @throws IOException
	 *             the repository could not be created in the temporary area
	 */
	protected Repository createWorkRepository() throws IOException {
		return createRepository(false /* not bare */);
	}

	/**
	 * Creates a new empty repository.
	 *
	 * @param bare
	 *            true to create a bare repository; false to make a repository
	 *            within its working directory
	 * @return the newly created repository, opened for access
	 * @throws IOException
	 *             the repository could not be created in the temporary area
	 */
	private Repository createRepository(boolean bare) throws IOException {
		String uniqueId = System.currentTimeMillis() + "_" + (testCount++);
		String gitdirName = "test" + uniqueId + (bare ? "" : "/") + ".git";
		File gitdir = new File(trash, gitdirName).getCanonicalFile();
		Repository db = new Repository(gitdir);

		assertFalse(gitdir.exists());
		db.create();
		toClose.add(db);
		return db;
	}

	/**
	 * Run a hook script in the repository, returning the exit status.
	 *
	 * @param db
	 *            repository the script should see in GIT_DIR environment
	 * @param hook
	 *            path of the hook script to execute, must be executable file
	 *            type on this platform
	 * @param args
	 *            arguments to pass to the hook script
	 * @return exit status code of the invoked hook
	 * @throws IOException
	 *             the hook could not be executed
	 * @throws InterruptedException
	 *             the caller was interrupted before the hook completed
	 */
	protected int runHook(final Repository db, final File hook,
			final String... args) throws IOException, InterruptedException {
		final String[] argv = new String[1 + args.length];
		argv[0] = hook.getAbsolutePath();
		System.arraycopy(args, 0, argv, 1, args.length);

		final Map<String, String> env = cloneEnv();
		env.put("GIT_DIR", db.getDirectory().getAbsolutePath());
		putPersonIdent(env, "AUTHOR", author);
		putPersonIdent(env, "COMMITTER", committer);

		final File cwd = db.getWorkDir();
		final Process p = Runtime.getRuntime().exec(argv, toEnvArray(env), cwd);
		p.getOutputStream().close();
		p.getErrorStream().close();
		p.getInputStream().close();
		return p.waitFor();
	}

	private static void putPersonIdent(final Map<String, String> env,
			final String type, final PersonIdent who) {
		final String ident = who.toExternalString();
		final String date = ident.substring(ident.indexOf("> ") + 2);
		env.put("GIT_" + type + "_NAME", who.getName());
		env.put("GIT_" + type + "_EMAIL", who.getEmailAddress());
		env.put("GIT_" + type + "_DATE", date);
	}

	/**
	 * Create a string to a UTF-8 temporary file and return the path.
	 *
	 * @param body
	 *            complete content to write to the file. If the file should end
	 *            with a trailing LF, the string should end with an LF.
	 * @return path of the temporary file created within the trash area.
	 * @throws IOException
	 *             the file could not be written.
	 */
	protected File write(final String body) throws IOException {
		final File f = File.createTempFile("temp", "txt", trash);
		try {
			write(f, body);
			return f;
		} catch (Error e) {
			f.delete();
			throw e;
		} catch (RuntimeException e) {
			f.delete();
			throw e;
		} catch (IOException e) {
			f.delete();
			throw e;
		}
	}

	/**
	 * Write a string as a UTF-8 file.
	 *
	 * @param f
	 *            file to write the string to. Caller is responsible for making
	 *            sure it is in the trash directory or will otherwise be cleaned
	 *            up at the end of the test. If the parent directory does not
	 *            exist, the missing parent directories are automatically
	 *            created.
	 * @param body
	 *            content to write to the file.
	 * @throws IOException
	 *             the file could not be written.
	 */
	protected void write(final File f, final String body) throws IOException {
		f.getParentFile().mkdirs();
		Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
		try {
			w.write(body);
		} finally {
			w.close();
		}
	}

	/**
	 * Fully read a UTF-8 file and return as a string.
	 *
	 * @param f
	 *            file to read the content of.
	 * @return UTF-8 decoded content of the file, empty string if the file
	 *         exists but has no content.
	 * @throws IOException
	 *             the file does not exist, or could not be read.
	 */
	protected String read(final File f) throws IOException {
		final byte[] body = IO.readFully(f);
		return new String(body, 0, body.length, "UTF-8");
	}

	private static String[] toEnvArray(final Map<String, String> env) {
		final String[] envp = new String[env.size()];
		int i = 0;
		for (Map.Entry<String, String> e : env.entrySet()) {
			envp[i++] = e.getKey() + "=" + e.getValue();
		}
		return envp;
	}

	private static HashMap<String, String> cloneEnv() {
		return new HashMap<String, String>(System.getenv());
	}

	private String testName() {
		return getClass().getName() + "." + getName();
	}
}