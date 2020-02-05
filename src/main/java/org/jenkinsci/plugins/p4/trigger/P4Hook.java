package org.jenkinsci.plugins.p4.trigger;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.UnprotectedRootAction;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHeadEvent;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.p4.review.ReviewProp;
import org.jenkinsci.plugins.p4.scm.events.P4BranchSCMHeadEvent;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

@Extension
public class P4Hook implements UnprotectedRootAction {

	ExecutorService executorService = Executors.newSingleThreadExecutor();

	public static final String URLNAME = "p4";

	@Override
	public String getIconFileName() {
		return "/plugin/p4/icons/helix-24px.png";
	}

	@Override
	public String getDisplayName() {
		return "P4 Trigger";
	}

	@Override
	public String getUrlName() {
		return URLNAME;
	}

	public void doEvent(StaplerRequest req) throws ServletException, IOException {

		// exit early if no json
		String contentType = req.getContentType();
		if (contentType == null || !contentType.startsWith("application/json")) {
			return;
		}

		String body = IOUtils.toString(req.getInputStream(), Charset.forName("UTF-8"));
		if (body.startsWith("payload=")) {
			body = body.substring(8);
		}
		JSONObject payload = JSONObject.fromObject(body);

		String typeString = payload.getString(ReviewProp.EVENT_TYPE.getProp());
		SCMEvent.Type eventType = SCMEvent.Type.valueOf(typeString);

		SCMHeadEvent.fireNow(new P4BranchSCMHeadEvent(eventType, payload, SCMEvent.originOf(req)));
	}

	public void doChange(StaplerRequest req) throws IOException {
		String body = IOUtils.toString(req.getInputStream(), Charset.forName("UTF-8"));
		String contentType = req.getContentType();
		if (contentType != null && contentType.startsWith("application/json")) {
			body = URLDecoder.decode(body, "UTF-8");
		}
		if (body.startsWith("payload=")) {
			body = body.substring(8);
			JSONObject payload = JSONObject.fromObject(body);

			final String port = payload.getString("p4port");
			//final String change = payload.getString("change");

			LOGGER.info("Received trigger event for: " + port);
			if (port == null) {
				LOGGER.warning("p4port must be specified");
				return;
			}

			// Use an executor to prevent blocking the trigger during polling
			executorService.submit(new Runnable() {

				@Override
				public void run() {
					final List<Job> jobs = getJobs();
					try {
						probeJobs(port, jobs);
					} catch (IOException e) {
						LOGGER.severe("Error on Polling Thread.");
						e.printStackTrace();
					}
				}
			});
		}
	}

	public void doChangeSubmit(StaplerRequest req, StaplerResponse rsp)
			throws IOException, ServletException {

		JSONObject formData = req.getSubmittedForm();
		if (!formData.isEmpty()) {
			String port = req.getParameter("_.p4port");
			//String change = req.getParameter("_.change");
			List<Job> jobs = getJobs();

			LOGGER.info("Manual trigger event: ");
			if (port != null) {
				probeJobs(port, jobs);
			} else {
				LOGGER.warning("p4port must be specified");
			}

			// send the user back.
			rsp.sendRedirect("../");
		}
	}

	private void probeJobs(@CheckForNull String port, List<Job> jobs) throws IOException {
		for (Job<?, ?> job : jobs) {
			if (!job.isBuildable()) {
				continue;
			}
			LOGGER.fine("P4: trying: " + job.getName());

			P4Trigger trigger = null;
			if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
				ParameterizedJobMixIn.ParameterizedJob pJob = (ParameterizedJobMixIn.ParameterizedJob) job;
				for (Object t : pJob.getTriggers().values()) {
					if (t instanceof P4Trigger) {
						trigger = (P4Trigger) t;
						break;
					}
				}
			}

			if (trigger != null) {
				LOGGER.info("P4: probing: " + job.getName());
				trigger.poke(job, port);
			} else {
				LOGGER.fine("P4: trigger not set: " + job.getName());
			}
		}
	}

	private List<Job> getJobs() {
		Jenkins j = Jenkins.getInstance();
		return j.getAllItems(Job.class);
	}

	final static Logger LOGGER = Logger.getLogger(P4Hook.class.getName());
}
