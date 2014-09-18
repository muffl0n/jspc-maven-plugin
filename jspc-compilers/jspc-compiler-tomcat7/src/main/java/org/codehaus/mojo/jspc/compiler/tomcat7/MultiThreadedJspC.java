package org.codehaus.mojo.jspc.compiler.tomcat7;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.jasper.JasperException;
import org.apache.jasper.JspC;
import org.apache.jasper.compiler.Localizer;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tools.ant.BuildException;

/**
 * Shell for the jspc compiler. Handles all options associated with the command
 * line and creates compilation contexts which it then compiles according to the
 * specified options.
 * 
 * This version can process files from a _single_ webapp at once, i.e. a single
 * docbase can be specified.
 * 
 * It can be used as an Ant task using:
 * 
 * <pre>
 *   &lt;taskdef classname="org.apache.jasper.JspC" name="jasper" &gt;
 *      &lt;classpath&gt;
 *          &lt;pathelement location="${java.home}/../lib/tools.jar"/&gt;
 *          &lt;fileset dir="${ENV.CATALINA_HOME}/lib"&gt;
 *              &lt;include name="*.jar"/&gt;
 *          &lt;/fileset&gt;
 *          &lt;path refid="myjars"/&gt;
 *       &lt;/classpath&gt;
 *  &lt;/taskdef&gt;
 * 
 *  &lt;jasper verbose="0"
 *           package="my.package"
 *           uriroot="${webapps.dir}/${webapp.name}"
 *           webXmlFragment="${build.dir}/generated_web.xml"
 *           outputDir="${webapp.dir}/${webapp.name}/WEB-INF/src/my/package" /&gt;
 * </pre>
 * 
 * @author Danno Ferrin
 * @author Pierre Delisle
 * @author Costin Manolache
 * @author Yoav Shapira
 */
public class MultiThreadedJspC extends JspC {
	// Logger
	private static final Log log = LogFactory.getLog(MultiThreadedJspC.class);

	private int threads = 10;
	private long compilationTimeoutMinutes = 30;

	public int getThreads() {
		return threads;
	}

	public void setThreads(int threads) {
		this.threads = threads;
	}

	public long getCompilationTimeoutMinutes() {
		return compilationTimeoutMinutes;
	}

	public void setCompilationTimeoutMinutes(long compilationTimeoutMinutes) {
		this.compilationTimeoutMinutes = compilationTimeoutMinutes;
	}

	/**
	 * Executes the compilation.
	 * 
	 * @throws JasperException
	 *             If an error occurs
	 */
	@Override
	public void execute() {
		if (log.isDebugEnabled()) {
			log.debug("execute() starting for " + pages.size() + " pages.");
		}

		try {
			if (uriRoot == null) {
				if (pages.size() == 0) {
					throw new JasperException(Localizer.getMessage("jsp.error.jspc.missingTarget"));
				}
				String firstJsp = pages.get(0);
				File firstJspF = new File(firstJsp);
				if (!firstJspF.exists()) {
					throw new JasperException(Localizer.getMessage("jspc.error.fileDoesNotExist", firstJsp));
				}
				locateUriRoot(firstJspF);
			}

			if (uriRoot == null) {
				throw new JasperException(Localizer.getMessage("jsp.error.jspc.no_uriroot"));
			}

			File uriRootF = new File(uriRoot);
			if (!uriRootF.isDirectory()) {
				throw new JasperException(Localizer.getMessage("jsp.error.jspc.uriroot_not_dir"));
			}

			if (context == null) {
				initServletContext();
			}

			// No explicit pages, we'll process all .jsp in the webapp
			if (pages.size() == 0) {
				scanFiles(uriRootF);
			}

			initWebXml();

			log.info("compiling " + pages.size() + " JSPs with " + getThreads() + " threads");
			ExecutorService executor = Executors.newFixedThreadPool(getThreads());
			final List<JasperException> errorCollector = Collections.synchronizedList(new ArrayList<JasperException>());

			for (String nextjsp : pages) {
				File fjsp = new File(nextjsp);
				if (!fjsp.isAbsolute()) {
					fjsp = new File(uriRootF, nextjsp);
				}
				if (!fjsp.exists()) {
					if (log.isWarnEnabled()) {
						log.warn(Localizer.getMessage(
								"jspc.error.fileDoesNotExist", fjsp.toString()));
					}
					continue;
				}
				String s = fjsp.getAbsolutePath();
				if (s.startsWith(uriRoot)) {
					nextjsp = s.substring(uriRoot.length());
				}
				if (nextjsp.startsWith("." + File.separatorChar)) {
					nextjsp = nextjsp.substring(2);
				}

				final String jspToCompile = nextjsp;
				executor.execute(new Runnable() {
					public void run() {
						try {
							processFile(jspToCompile);
						} catch (JasperException je) {
							errorCollector.add(je);
						}
					}
				});
			}

			executor.shutdown();
			log.info("executor shutdown");
			log.info("awaiting termination: " + compilationTimeoutMinutes + "m");
			boolean awaitTermination = executor.awaitTermination(compilationTimeoutMinutes, TimeUnit.MINUTES);
			log.info("awaiting termination: " + awaitTermination);

			if (errorCollector.size() > 0) {
				throwBuildException(errorCollector);
			}

			completeWebXml();

			if (addWebXmlMappings) {
				mergeIntoWebXml();
			}

		} catch (IOException ioe) {
			throw new BuildException(ioe);

		} catch (JasperException je) {
			throwBuildException(Arrays.asList(je));
		} catch (InterruptedException e) {
			throw new BuildException(e);
		} finally {
			if (loader != null) {
				LogFactory.release(loader);
			}
		}
	}

	private void throwBuildException(List<JasperException> errorCollector) {
		StringBuilder errOut = new StringBuilder();

		for (JasperException je : errorCollector) {
			Throwable rootCause = je;
			while (rootCause instanceof JasperException
					&& ((JasperException) rootCause).getRootCause() != null) {
				rootCause = ((JasperException) rootCause).getRootCause();
			}
			if (rootCause != errorCollector) {
				rootCause.printStackTrace();
			}
			errOut.append(rootCause.getMessage()).append('\n');
		}

		// throw exception with first error encountered as cause, but all
		// messages
		throw new BuildException(errOut.toString(), errorCollector.get(0));
	}

}