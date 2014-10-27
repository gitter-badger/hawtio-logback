package ch.mimacom.log.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.CyclicBufferAppender;
import ch.qos.logback.core.spi.AppenderAttachable;
import io.fabric8.insight.log.LogEvent;
import io.fabric8.insight.log.LogFilter;
import io.fabric8.insight.log.LogResults;
import io.fabric8.insight.log.support.LogQuerySupport;
import io.fabric8.insight.log.support.Predicate;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;


public class LogbackAwareLogQueryMBeanImpl extends LogQuerySupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogbackAwareLogQueryMBeanImpl.class);

    private final CyclicBufferAppender<ILoggingEvent> cyclicBufferAppender = new ILoggingEventAwareCyclicBufferAppender();

    private final int maxLogsBufferSize;

    public LogbackAwareLogQueryMBeanImpl(int maxLogsBufferSize) {
        if (!(maxLogsBufferSize > 0)) {
            throw new IllegalArgumentException("A positive 'maxLogsBufferSize' greater than 0 must be set");
        }
        this.maxLogsBufferSize = maxLogsBufferSize;
    }

    @PostConstruct
    public void start() {
        super.start();
        attachAdapter();
    }

    @PreDestroy
    public void stop() {
        super.stop();
    }

    @Override
    public LogResults getLogResults(int maxCount) throws IOException {
        return filterLogResults(null, maxCount);
    }

    @Override
    public LogResults queryLogResults(LogFilter filter) {
        Predicate<LogEvent> predicate = createPredicate(filter);
        int maxCount = -1;
        if (filter != null) {
            maxCount = filter.getCount();
        }
        return filterLogResults(predicate, maxCount);
    }

    @Override
    public String filterLogEvents(String s) throws IOException {
        return null;
    }

    private void attachAdapter() {
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        AppenderAttachable appenderAttachable = null;
        if (loggerFactory instanceof AppenderAttachable) {
            appenderAttachable = (AppenderAttachable) loggerFactory;
        }
        if (appenderAttachable == null) {
            Logger root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            appenderAttachable = (AppenderAttachable) root;
        }
        if (appenderAttachable != null) {
            cyclicBufferAppender.setName("LogQueryCyclicBufferAppender");
            cyclicBufferAppender.setMaxSize(maxLogsBufferSize);
            cyclicBufferAppender.start();
            appenderAttachable.addAppender(cyclicBufferAppender);
        } else {
            LOGGER.error("No ILoggerFactory found so cannot attach appender!");
        }
    }

    private Predicate<LogEvent> createPredicate(LogFilter filter) {
        if (filter == null) {
            return null;
        }
        final List<Predicate<LogEvent>> predicates = new ArrayList<Predicate<LogEvent>>();

        final Set<String> levels = filter.getLevelsSet();
        if (levels.size() > 0) {
            predicates.add(LogEventPredicateFactory.buildLevelsPredicate(levels));
        }

        final Long before = filter.getBeforeTimestamp();
        if (before != null) {
            final Date date = new Date(before);
            predicates.add(LogEventPredicateFactory.buildDatePredicate(date));
        }

        final Long after = filter.getAfterTimestamp();
        if (after != null) {
            final Date date = new Date(after);
            predicates.add(LogEventPredicateFactory.buildTimestampPredicate(date));
        }

        final String matchesText = filter.getMatchesText();
        if (matchesText != null && !matchesText.isEmpty()) {
            predicates.add(LogEventPredicateFactory.buildTextMatcherPredicate(matchesText));
        }

        if (predicates.size() == 0) {
            return null;
        } else if (predicates.size() == 1) {
            return predicates.get(0);
        } else {
            return new Predicate<LogEvent>() {
                @Override
                public String toString() {
                    return "AndPredicate" + predicates;
                }

                @Override
                public boolean matches(LogEvent event) {
                    for (Predicate<LogEvent> predicate : predicates) {
                        if (!predicate.matches(event)) {
                            return false;
                        }
                    }
                    return true;
                }
            };
        }
    }


    private LogResults filterLogResults(Predicate<LogEvent> predicate, int maxCount) {
        int matched = 0;
        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        final List<LogEvent> list = new ArrayList<LogEvent>();
        for (int i = 0; i < cyclicBufferAppender.getLength(); i++) {
            ILoggingEvent element = cyclicBufferAppender.get(i);
            LogEvent logEvent = LogEventAssembler.toLogEvent(element, getHostName());
            long timestamp = element.getTimeStamp();
            if (timestamp > to) {
                to = timestamp;
            }
            if (timestamp < from) {
                from = timestamp;
            }
            if (logEvent != null) {
                if (predicate == null || predicate.matches(logEvent)) {
                    list.add(logEvent);
                    matched += 1;
                    if (maxCount > 0 && matched >= maxCount) {
                        break;
                    }
                }
            }
        }
        LogResults results = new LogResults();
        results.setEvents(list);
        if (from < Long.MAX_VALUE) {
            results.setFromTimestamp(from);
        }
        if (to > Long.MIN_VALUE) {
            results.setToTimestamp(to);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Requested " + maxCount + " logging items. returning "
                    + results.getEvents().size() + " event(s) from a possible " + cyclicBufferAppender.getLength());

        }
        return results;
    }

    public static class LogEventPredicateFactory {
        private static Predicate<LogEvent> buildTextMatcherPredicate(final String matchesText) {
            return new Predicate<LogEvent>() {
                @Override
                public boolean matches(LogEvent event) {
                    // TODO pado
                    return true;
                }
            };
        }

        private static Predicate<LogEvent> buildTimestampPredicate(final Date date) {
            return new Predicate<LogEvent>() {
                @Override
                public boolean matches(LogEvent event) {
                    Date time = event.getTimestamp();
                    return time != null && time.after(date);
                }
            };
        }

        private static Predicate<LogEvent> buildDatePredicate(final Date date) {
            return new Predicate<LogEvent>() {
                @Override
                public boolean matches(LogEvent event) {
                    Date time = event.getTimestamp();
                    return time != null && time.before(date);
                }
            };
        }

        private static Predicate<LogEvent> buildLevelsPredicate(final Set<String> levels) {
            return new Predicate<LogEvent>() {
                @Override
                public boolean matches(LogEvent event) {
                    String level = event.getLevel();
                    return level != null && levels.contains(level);
                }
            };
        }
    }

    public static class LogEventAssembler {

        private static LogEvent toLogEvent(ILoggingEvent element, String hostName) {
            LogEvent answer = new LogEvent();

            // TODO
            //answer.setContainerName(element.get);
            IThrowableProxy throwableInformation = element.getThrowableProxy();
            if (throwableInformation != null) {
                StackTraceElementProxy[] stepArray = throwableInformation.getStackTraceElementProxyArray();
                List<String> exceptionStrings = new ArrayList<String>(stepArray.length);
                for (StackTraceElementProxy step : stepArray) {
                    exceptionStrings.add(step.toString());
                }
                answer.setException(exceptionStrings.toArray(new String[exceptionStrings.size()]));
            }

            StackTraceElement[] callerData = element.getCallerData();
            if (callerData.length != 0) {
                StackTraceElement stackTraceElement = callerData[0];
                answer.setClassName(stackTraceElement.getClassName());
                answer.setFileName(stackTraceElement.getFileName());
                answer.setClassName(stackTraceElement.getClassName());
                answer.setMethodName(stackTraceElement.getMethodName());
                answer.setLineNumber(Integer.toString(stackTraceElement.getLineNumber()));
            }
            Level level = element.getLevel();
            if (level != null) {
                answer.setLevel(level.toString());
            }
            answer.setLogger(element.getLoggerName());
            String message = element.getFormattedMessage();
            if (message != null) {
                answer.setMessage(message);
            }
            answer.setProperties(element.getMDCPropertyMap());
            answer.setSeq(element.getTimeStamp());
            answer.setTimestamp(new Date(element.getTimeStamp()));
            answer.setThread(element.getThreadName());
            answer.setHost(hostName);
            return answer;
        }
    }

    public static class ILoggingEventAwareCyclicBufferAppender extends CyclicBufferAppender<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent eventObject) {
            super.append(eventObject);
            // This will make sure the caller-data is initialized in-memory for latter usage
            eventObject.getCallerData();
        }
    }
}