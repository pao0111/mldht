/*
 *    This file is part of mlDHT.
 * 
 *    mlDHT is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 2 of the License, or
 *    (at your option) any later version.
 * 
 *    mlDHT is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 * 
 *    You should have received a copy of the GNU General Public License
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>.
 */
package lbms.plugins.mldht.kad.tasks;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import lbms.plugins.mldht.kad.DHT;
import lbms.plugins.mldht.kad.DHT.LogLevel;
import lbms.plugins.mldht.kad.DHTConstants;
import lbms.plugins.mldht.kad.KBucketEntry;
import lbms.plugins.mldht.kad.Key;
import lbms.plugins.mldht.kad.Node;
import lbms.plugins.mldht.kad.RPCCall;
import lbms.plugins.mldht.kad.RPCCallListener;
import lbms.plugins.mldht.kad.RPCServer;
import lbms.plugins.mldht.kad.messages.MessageBase;

/**
 * Performs a task on K nodes provided by a KClosestNodesSearch.
 * This is a base class for all tasks.
 *
 * @author Damokles
 */
public abstract class Task implements RPCCallListener, Comparable<Task> {

	protected NavigableSet<KBucketEntry>	todo;			// nodes todo
	protected NavigableSet<Key>			inFlight;
	protected NavigableSet<Key>			stalled;
	
	protected Node						node;

	protected Key						targetKey;

	protected String					info;
	protected RPCServer					rpc;
	
	private HashSet						visited = new HashSet();
	private AtomicInteger				outstandingRequestsExcludingStalled = new AtomicInteger();
	private AtomicInteger				outstandingRequests = new AtomicInteger();
	private int							sentReqs;
	private int							recvResponses;
	long 								startTime;
	long								firstResultTime;
	long								finishTime;
	private int							failedReqs;
	private int							taskID;
	private boolean						taskFinished;
	private boolean						queued;
	private List<TaskListener>			listeners;

	/**
	 * Create a task.
	 * @param rpc The RPC server to do RPC calls
	 * @param node The node
	 */
	Task (Key target, RPCServer rpc, Node node) {
		if(rpc == null)
			throw new IllegalArgumentException("RPC must not be null");
		this.targetKey = target;
		this.rpc = rpc;
		this.node = node;
		queued = true;
		
	
		todo = new ConcurrentSkipListSet<KBucketEntry>(new KBucketEntry.DistanceOrder(targetKey));
		
		
		Comparator<Key> distanceOrder = new Key.DistanceOrder(targetKey);
		
		inFlight = new ConcurrentSkipListSet<>(distanceOrder);
		stalled = new ConcurrentSkipListSet<>(distanceOrder);
		
		taskFinished = false;
	}

	/**
	 * @param rpc The RPC server to do RPC calls
	 * @param node The node
	 * @param info info that should be displayed to the user, eg. download name on announce task
	 */
	Task (Key target, RPCServer rpc, Node node, String info) {
		this(target, rpc, node);
		this.info = info;
	}
	
	protected void visited(KBucketEntry e)
	{
		synchronized (visited)
		{
			visited.add(e.getAddress().getAddress());
			visited.add(e.getID());
		}
	}
	
	protected boolean hasVisited(KBucketEntry e)
	{
		synchronized (visited)
		{
			return visited.contains(e.getAddress().getAddress()) || visited.contains(e.getID());
		}
	}
	
	
	public RPCServer getRPC() {
		return rpc;
	}
	

	public int compareTo(Task o) {
		return taskID - o.taskID;
	}
	
	@Override
	public int hashCode() {
		return taskID;
	}

	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCCallListener#onResponse(lbms.plugins.mldht.kad.RPCCall, lbms.plugins.mldht.kad.messages.MessageBase)
	 */
	public void onResponse (RPCCall c, MessageBase rsp) {
		if (!isFinished())
			callFinished(c, rsp);
		
		stalled.remove(c.getExpectedID());
		inFlight.remove(c.getExpectedID());
		
		// only decrement counters after we have processed message payloads
		if(!c.wasStalled())
			outstandingRequestsExcludingStalled.decrementAndGet();
		outstandingRequests.decrementAndGet();

		recvResponses++;


			
		runStuff();
	}
	
	public void onStall(RPCCall c)
	{
		stalled.add(c.getExpectedID());
		inFlight.remove(c.getExpectedID());
		outstandingRequestsExcludingStalled.decrementAndGet();
		
		runStuff();
	}

	/* (non-Javadoc)
	 * @see lbms.plugins.mldht.kad.RPCCallListener#onTimeout(lbms.plugins.mldht.kad.RPCCall)
	 */
	public void onTimeout (RPCCall c) {
		
		stalled.remove(c.getExpectedID());
		inFlight.remove(c.getExpectedID());
		
		if(!c.wasStalled())
			outstandingRequestsExcludingStalled.decrementAndGet();
		outstandingRequests.decrementAndGet();

		failedReqs++;

		if (!isFinished())
			callTimeout(c);

		runStuff();
	}

	/**
	 *  Start the task, to be used when a task is queued.
	 */
	public void start () {
		if (queued) {
			DHT.logDebug("Starting Task taskID: " + toString());
			queued = false;
			startTime = System.currentTimeMillis();
			try
			{
				runStuff();
			} catch (Exception e)
			{
				DHT.log(e, LogLevel.Error);
			}
		}
	}
	
	private void runStuff() {
		if(isDone())
			finished();
		
		if (canDoRequest() && !isFinished()) {
			update();

			// check again in case todo-queue has been drained by update()
			if(isDone())
				finished();
		}
		

	}

	/**
	 * Will continue the task, this will be called every time we have
	 * rpc slots available for this task. Should be implemented by derived classes.
	 */
	abstract void update ();

	/**
	 * A call is finished and a response was received.
	 * @param c The call
	 * @param rsp The response
	 */
	abstract void callFinished (RPCCall c, MessageBase rsp);
	
	/**
	 * A call timedout
	 * @param c The call
	 */
	abstract void callTimeout (RPCCall c);

	/**
	 * Do a call to the rpc server, increments the outstanding_reqs variable.
	 * @param req THe request to send
	 * @return true if call was made, false if not
	 */
	boolean rpcCall (MessageBase req, Key expectedID, Consumer<RPCCall> modifyCallBeforeSubmit) {
		if (!canDoRequest()) {
			// if we reject a request we need something to wakeup the task later
			rpc.onDeclog(this::runStuff);
			return false;
		}
		
		RPCCall call = new RPCCall(req).setExpectedID(expectedID).addListener(this);
		
		if(modifyCallBeforeSubmit != null)
			modifyCallBeforeSubmit.accept(call);

		inFlight.add(expectedID);
		outstandingRequestsExcludingStalled.incrementAndGet();
		outstandingRequests.incrementAndGet();
		
		// asyncify since we're under a lock here
		rpc.getDHT().getScheduler().execute(() -> rpc.doCall(call)) ;

		
		sentReqs++;
		return true;
	}

	/// See if we can do a request
	boolean canDoRequest () {
		return outstandingRequestsExcludingStalled.get() < DHTConstants.MAX_CONCURRENT_REQUESTS;
	}
	
	boolean hasUnfinishedRequests() {
		return outstandingRequests.get() > 0;
	}

	/// Is the task finished
	public boolean isFinished () {
		return taskFinished;
	}

	/// Set the task ID
	void setTaskID (int tid) {
		taskID = tid;
	}

	/// Get the task ID
	public int getTaskID () {
		return taskID;
	}

	/**
	 * @return the Count of Failed Requests
	 */
	public int getFailedReqs () {
		return failedReqs;
	}

	/**
	 * @return the Count of Received Responses
	 */
	public int getRecvResponses () {
		return recvResponses;
	}

	/**
	 * @return the Count of Sent Requests
	 */
	public int getSentReqs () {
		return sentReqs;
	}

	public int getTodoCount () {
		return todo.size();
	}

	/**
	 * @return the targetKey
	 */
	public Key getTargetKey () {
		return targetKey;
	}

	/**
	 * @return the info
	 */
	public String getInfo () {
		return info;
	}
	
	public long getStartTime() {
		return startTime;
	}
	
	public long getFinishedTime() {
		return finishTime;
	}
	
	public long getFirstResultTime() {
		return firstResultTime;
	}

	/**
	 * @param info the info to set
	 */
	public void setInfo (String info) {
		this.info = info;
	}

	public void addToTodo (KBucketEntry e) {
		synchronized (todo) {
			todo.add(e);
		}
	}

	/**
	 * @return number of requests that this task is actively waiting for
	 */
	public int getNumOutstandingRequestsExcludingStalled () {
		return outstandingRequestsExcludingStalled.get();
	}
	
	/**
	 * @return number of requests that still haven't reached their final state but might have stalled
	 */
	public int getNumOutstandingRequests() {
		return outstandingRequests.get();
	}

	public boolean isQueued () {
		return queued;
	}

	/// Kills the task
	public void kill () {
		finishTime = -1;
		finished();
	}

	/**
	 * Add a node to the todo list
	 * @param ip The ip or hostname of the node
	 * @param port The port
	 */
	public void addDHTNode (InetAddress ip, int port) {
		InetSocketAddress addr = new InetSocketAddress(ip, port);
		synchronized (todo) {
			todo.add(new KBucketEntry(addr, Key.createRandomKey()));
		}
	}

	/**
	 * The task is finsihed.
	 * @param t The Task
	 */
	private void finished () {
		synchronized (this)
		{
			if(taskFinished)
				return;
			taskFinished = true;
		}
		
		if(finishTime != -1)
			finishTime = System.currentTimeMillis();
		
		DHT.logDebug("Task "+getTaskID()+" finished: " + toString());

		if (listeners != null) {
			for (TaskListener tl : listeners) {
				tl.finished(this);
			}
		}
	}
	
	protected abstract boolean isDone();

	public void addListener (TaskListener listener) {
		if (listeners == null) {
			listeners = new ArrayList<TaskListener>(1);
		}
		// listener is added after the task already terminated, thus it won't get the event, trigger it manually
		if(taskFinished)
			listener.finished(this);
		listeners.add(listener);
	}

	public void removeListener (TaskListener listener) {
		if (listeners != null) {
			listeners.remove(listener);
		}
	}
	
	public Duration age() {
		return Duration.between(Instant.ofEpochMilli(startTime), Instant.now());
	}
	
	@Override
	public String toString() {
		StringBuilder b = new StringBuilder(100);
		b.append(this.getClass().getSimpleName());
		b.append(" target:").append(targetKey);
		b.append(" todo:").append(todo.size());
		if(!queued) {
			b.append(" sent:").append(sentReqs);
			b.append(" recv:").append(recvResponses);
			
		}
		b.append(" srv: ").append(rpc.getDerivedID());
		
		if(finishTime == -1) {
			b.append(" killed");
		}
		if(taskFinished) {
			b.append(" finished");
		}
		
		if(startTime != 0) {
			if(finishTime == 0)
				b.append(" age:").append(age());
			else if(finishTime > 0)
				b.append(" time to finish:").append(Duration.between(Instant.ofEpochMilli(startTime), Instant.ofEpochMilli(finishTime)));
		}
		
		b.append(" name:").append(info);
		
		return b.toString();
	}
}
