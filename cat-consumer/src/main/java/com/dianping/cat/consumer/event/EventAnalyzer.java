package com.dianping.cat.consumer.event;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;

import com.dianping.cat.Cat;
import com.dianping.cat.consumer.event.model.entity.EventName;
import com.dianping.cat.consumer.event.model.entity.EventReport;
import com.dianping.cat.consumer.event.model.entity.EventType;
import com.dianping.cat.consumer.event.model.entity.Range;
import com.dianping.cat.consumer.event.model.transform.DefaultXmlBuilder;
import com.dianping.cat.consumer.event.model.transform.DefaultXmlParser;
import com.dianping.cat.message.Event;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.spi.AbstractMessageAnalyzer;
import com.dianping.cat.message.spi.MessagePathBuilder;
import com.dianping.cat.message.spi.MessageTree;
import com.dianping.cat.storage.Bucket;
import com.dianping.cat.storage.BucketManager;
import com.site.lookup.annotation.Inject;

public class EventAnalyzer extends AbstractMessageAnalyzer<EventReport> implements LogEnabled {
	private static final long MINUTE = 60 * 1000;

	@Inject
	private BucketManager m_bucketManager;

	@Inject
	private MessagePathBuilder m_pathBuilder;

	private Map<String, EventReport> m_reports = new HashMap<String, EventReport>();

	private long m_extraTime;

	private Logger m_logger;

	private long m_startTime;

	private long m_duration;

	void closeMessageBuckets() {
		Date timestamp = new Date(m_startTime);

		for (String domain : m_reports.keySet()) {
			Bucket<MessageTree> logviewBucket = null;

			try {
				logviewBucket = m_bucketManager.getLogviewBucket(m_startTime, domain);
			} catch (Exception e) {
				m_logger.error(String.format("Error when getting logview bucket of %s!", timestamp), e);
			} finally {
				if (logviewBucket != null) {
					m_bucketManager.closeBucket(logviewBucket);
				}
			}
		}
	}

	@Override
	public void doCheckpoint() throws IOException {
		storeReports(m_reports.values());
		closeMessageBuckets();
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	protected List<EventReport> generate() {
		List<EventReport> reports = new ArrayList<EventReport>(m_reports.size());
		StatisticsComputer computer = new StatisticsComputer();

		for (String domain : m_reports.keySet()) {
			EventReport report = getReport(domain);

			report.accept(computer);
			reports.add(report);
		}

		return reports;
	}

	public EventReport getReport(String domain) {
		EventReport report = m_reports.get(domain);

		if (report != null) {
			List<String> sortedDomains = getSortedDomains(m_reports.keySet());

			for (String e : sortedDomains) {
				report.addDomain(e);
			}
		}

		return report;
	}

	@Override
	protected boolean isTimeout() {
		long currentTime = System.currentTimeMillis();
		long endTime = m_startTime + m_duration + m_extraTime;

		return currentTime > endTime;
	}

	void loadReports() {
		DefaultXmlParser parser = new DefaultXmlParser();
		Bucket<String> reportBucket = null;

		try {
			reportBucket = m_bucketManager.getReportBucket(m_startTime, "event");

			for (String id : reportBucket.getIds()) {
				String xml = reportBucket.findById(id);
				EventReport report = parser.parse(xml);

				m_reports.put(report.getDomain(), report);
			}
		} catch (Exception e) {
			m_logger.error(String.format("Error when loading event reports of %s!", new Date(m_startTime)), e);
		} finally {
			if (reportBucket != null) {
				m_bucketManager.closeBucket(reportBucket);
			}
		}
	}

	@Override
	protected void process(MessageTree tree) {
		String domain = tree.getDomain();
		EventReport report = m_reports.get(domain);

		if (report == null) {
			report = new EventReport(domain);
			report.setStartTime(new Date(m_startTime));
			report.setEndTime(new Date(m_startTime + MINUTE * 60 - 1));

			m_reports.put(domain, report);
		}

		Message message = tree.getMessage();

		int count = 0;

		if (message instanceof Transaction) {
			count += processTransaction(report, tree, (Transaction) message);
		} else if (message instanceof Event) {
			count += processEvent(report, tree, (Event) message);
		}

		// the message is required by some events
		if (count > 0) {
			storeMessage(tree);
		}
	}

	int processEvent(EventReport report, MessageTree tree, Event event) {
		EventType type = report.findOrCreateType(event.getType());
		EventName name = type.findOrCreateName(event.getName());
		String url = m_pathBuilder.getLogViewPath(tree.getMessageId());
		int count = 0;

		synchronized (type) {
			type.incTotalCount();
			name.incTotalCount();

			if (event.isSuccess()) {
				if (type.getSuccessMessageUrl() == null) {
					type.setSuccessMessageUrl(url);
					count++;
				}

				if (name.getSuccessMessageUrl() == null) {
					name.setSuccessMessageUrl(url);
					count++;
				}
			} else {
				type.incFailCount();
				name.incFailCount();

				if (type.getFailMessageUrl() == null) {
					type.setFailMessageUrl(url);
					count++;
				}

				if (name.getFailMessageUrl() == null) {
					name.setFailMessageUrl(url);
					count++;
				}
			}
		}

		processEventGrpah(name, event);

		return count;
	}

	void processEventGrpah(EventName name, Event t) {
		Calendar cal = Calendar.getInstance();
		cal.setTimeInMillis(t.getTimestamp());
		int min = cal.get(Calendar.MINUTE);
		int tk = min - min % 5;

		synchronized (name) {
			Range range = name.findOrCreateRange(tk);

			range.incCount();

			if (!t.isSuccess()) {
				range.incFails();
			}
		}
	}

	int processTransaction(EventReport report, MessageTree tree, Transaction t) {
		List<Message> children = t.getChildren();
		int count = 0;

		for (Message child : children) {
			if (child instanceof Transaction) {
				count += processTransaction(report, tree, (Transaction) child);
			} else if (child instanceof Event) {
				count += processEvent(report, tree, (Event) child);
			}
		}

		return count;
	}

	public void setAnalyzerInfo(long startTime, long duration, long extraTime) {
		m_extraTime = extraTime;
		m_startTime = startTime;
		m_duration = duration;

		loadReports();
	}

	@Override
	protected void store(List<EventReport> reports) {
		if (reports == null || reports.size() == 0) {
			return;
		}

		storeReports(reports);
		closeMessageBuckets();
	}

	void storeMessage(MessageTree tree) {
		String messageId = tree.getMessageId();
		String domain = tree.getDomain();

		try {
			Bucket<MessageTree> logviewBucket = m_bucketManager.getLogviewBucket(m_startTime, domain);

			logviewBucket.storeById(messageId, tree);
		} catch (IOException e) {
			m_logger.error("Error when storing logview for event analyzer!", e);
		}
	}

	void storeReports(Collection<EventReport> reports) {
		DefaultXmlBuilder builder = new DefaultXmlBuilder(true);
		Bucket<String> reportBucket = null;
		Transaction t = Cat.getProducer().newTransaction("Checkpoint", getClass().getSimpleName());

		try {
			reportBucket = m_bucketManager.getReportBucket(m_startTime, "event");

			for (EventReport report : reports) {
				String xml = builder.buildXml(report);
				String domain = report.getDomain();

				reportBucket.storeById(domain, xml);
			}

			t.setStatus(Message.SUCCESS);
		} catch (Exception e) {
			Cat.getProducer().logError(e);
			t.setStatus(e);
			m_logger.error(String.format("Error when storing event reports of %s!", new Date(m_startTime)), e);
		} finally {
			t.complete();

			if (reportBucket != null) {
				m_bucketManager.closeBucket(reportBucket);
			}
		}
	}
}
