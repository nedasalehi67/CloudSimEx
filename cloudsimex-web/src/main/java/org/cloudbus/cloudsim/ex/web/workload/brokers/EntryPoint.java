package org.cloudbus.cloudsim.ex.web.workload.brokers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.ex.disk.HddVm;
import org.cloudbus.cloudsim.ex.geolocation.IGeolocationService;
import org.cloudbus.cloudsim.ex.util.CustomLog;
import org.cloudbus.cloudsim.ex.vm.VMStatus;
import org.cloudbus.cloudsim.ex.web.ILoadBalancer;
import org.cloudbus.cloudsim.ex.web.WebSession;

/**
 * Paper algorithm implementation.
 * 
 * @author nikolay.grozev
 * 
 */
public class EntryPoint extends BaseEntryPoint implements IEntryPoint {

    private static final double OVERLOAD_UTIL = 0.7;

    private final CloudPriceComparator costComparator;

    private final double latencySLA;

    /**
     * Constr.
     * 
     * @param geoService
     *            - provides the IP utilities needed by the entry point. Must
     *            not be null.
     * @param appId
     *            - the id of the application this entry point services. Must
     *            not be null.
     * @param latencySLA
     *            - the latency SLA of the application.
     */
    public EntryPoint(final IGeolocationService geoService, final long appId, final double latencySLA) {
	super(geoService, appId);
	this.latencySLA = latencySLA;

	costComparator = new CloudPriceComparator(appId);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.cloudbus.cloudsim.ex.web.workload.brokers.IEntryPoint#dispatchSessions
     * (java.util.List)
     */
    @Override
    public void dispatchSessions(final List<WebSession> webSessions) {
	super.dispatchSessions(webSessions);
	
	costComparator.prepareToCompare();
	Collections.sort(getBrokers(), costComparator);

	// A table of assignments of web sessions to brokers/clouds.
	Map<WebBroker, List<WebSession>> assignments = new HashMap<>();
	for (WebBroker broker : getBrokers()) {
	    assignments.put(broker, new ArrayList<WebSession>());
	}

	// Decide which broker/cloud will serve each session - populate the
	// assignments table accordingly
	for (WebSession sess : webSessions) {
	    List<WebBroker> eligibleBrokers = filterBrokers(getBrokers(), sess, getAppId());
	    // Collections.sort(eligibleBrokers, costComparator);

	    WebBroker selectedBroker = null;
	    double bestLatencySoFar = Double.MAX_VALUE;
	    for (WebBroker eligibleBroker : eligibleBrokers) {
		ILoadBalancer balancer = eligibleBroker.getLoadBalancers().get(getAppId());
		if (balancer != null) {
		    String ip = balancer.getIp();
		    String clientIP = sess.getSourceIP();
		    double latency = getGeoService().latency(ip, clientIP);

		    if (latency < latencySLA) {
			selectedBroker = eligibleBroker;
			break;
		    } else if (bestLatencySoFar > latency) {
			selectedBroker = eligibleBroker;
			bestLatencySoFar = latency;
		    }
		}
	    }

	    if (selectedBroker == null) {
		CustomLog.printConcat("[Entry Point] Session ", sess.getSessionId(), " has been denied service.");
		getCanceledSessions().add(sess);
	    } else {
		assignments.get(selectedBroker).add(sess);
		sess.setServerIP(selectedBroker.getLoadBalancers().get(getAppId()).getIp());
	    }
	}

	// Submit the sessions to the selected brokers/clouds
	for (Map.Entry<WebBroker, List<WebSession>> entry : assignments.entrySet()) {
	    WebBroker broker = entry.getKey();
	    List<WebSession> sessions = entry.getValue();
	    for (WebSession sess : sessions) {
		CustomLog.printf("[Entry Point] Session %d will be assigned to %s", sess.getSessionId(),
			broker.toString());
	    }
	    broker.submitSessionsDirectly(sessions, getAppId());
	}
    }

    private List<WebBroker> filterBrokers(final List<WebBroker> brokers2, final WebSession sess, final long appId) {
	List<WebBroker> eligibleBrokers = new ArrayList<>();
	for (WebBroker b : brokers2) {
	    if (sess.getMetadata() != null && sess.getMetadata().length > 0 &&
		    b.getMetadata() != null && b.getMetadata().length > 0 &&
		    sess.getMetadata()[0].equals(b.getMetadata()[0])) {
		eligibleBrokers.add(b);
	    }
	}
	return eligibleBrokers;
    }

    private static class CloudPriceComparator implements Comparator<WebBroker> {
	private long appId;

	// We do not want to call getASServersToNumSessions() all the time,
	// since it can be resource intensive operation. Thus we cache the
	// values.
	private Map<WebBroker, Map<Integer, Integer>> brokersToMaps = new HashMap<>();
	private Map<WebBroker, Boolean> overloadedDBLayer = new HashMap<>();

	public CloudPriceComparator(final long appId) {
	    super();
	    this.appId = appId;
	}

	/**
	 * Should be called before this comparator is used to sort a collection.
	 */
	public void prepareToCompare() {
	    brokersToMaps.clear();
	    overloadedDBLayer.clear();
	}

	@Override
	public int compare(final WebBroker b1, final WebBroker b2) {
	    return Double.compare(definePrice(b1), definePrice(b2));
	}

	public double definePrice(final WebBroker b) {
	    if (isDBLayerOverloaded(b)) {
		return Double.MAX_VALUE;
	    } else {
		ILoadBalancer lb = b.getLoadBalancers().get(appId);
		BigDecimal pricePerMinute = b.getVMBillingPolicy().normalisedCostPerMinute(lb.getAppServers().get(0));
		Map<Integer, Integer> srvToNumSessions = brokersToMaps.get(b);
		if (srvToNumSessions == null) {
		    srvToNumSessions = b.getASServersToNumSessions();
		    brokersToMaps.put(b, srvToNumSessions);
		}

		int numRunning = 0;
		double sumAvg = 0;
		for (HddVm vm : lb.getAppServers()) {
		    double cpuUtil = vm.getCPUUtil();
		    double ramUtil = vm.getRAMUtil();
		    if (vm.getStatus() == VMStatus.RUNNING && srvToNumSessions.containsKey(vm.getId()) && (cpuUtil > 0 || ramUtil > 0)) {
			numRunning++;
			int numSessions = srvToNumSessions.get(vm.getId());
			double nCapacity = numSessions / Math.max(cpuUtil, ramUtil); // f(vm)
			sumAvg += 1 / nCapacity; // sum(1/f(vm))
		    }
		}

		double avgSessionsPerVm = numRunning == 0 ? 0 : sumAvg / numRunning; //sum(1/f(vm)) / |V|
		return pricePerMinute.doubleValue() * avgSessionsPerVm; //p * sum(1/f(vm)) / |V|
	    }
	}

	private boolean isDBLayerOverloaded(WebBroker b) {
	    if (overloadedDBLayer.containsKey(b)) {
		return overloadedDBLayer.get(b);
	    } else {
		boolean result = true;
		ILoadBalancer lb = b.getLoadBalancers().get(appId);
		for (HddVm db : lb.getDbBalancer().getVMs()) {
		    if (db.getStatus() == VMStatus.RUNNING && db.getCPUUtil() < OVERLOAD_UTIL
			    && db.getRAMUtil() < OVERLOAD_UTIL && db.getDiskUtil() < OVERLOAD_UTIL) {
			result = false;
			break;
		    }
		}
		overloadedDBLayer.put(b, result);

		if (result) {
		    CustomLog.printf("[Entry Point] Broker (%s) has overloaded DB layer", b);
		}

		return result;
	    }
	}
    }
}
