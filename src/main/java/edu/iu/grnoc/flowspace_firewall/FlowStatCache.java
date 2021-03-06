/*
 Copyright 2014 Trustees of Indiana University

   Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package edu.iu.grnoc.flowspace_firewall;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import net.floodlightcontroller.core.IOFSwitch;

import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionType;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.Wildcards;

/**
 * Stores the stats for all of the switches
 * for quick retreiveal without overloading
 * the switches.  The Synchronized methods give
 * essentially a mutex for accessing the data
 * @author aragusa
 *
 */

public class FlowStatCache{

	//the logger
	private static final Logger log = LoggerFactory.getLogger(FlowStatCache.class);
	//the cache
	private HashMap<Long, List<OFStatistics>> flowStats;
	private HashMap<Long, HashMap<Short, OFStatistics>> portStats;
	private HashMap<Long, HashMap<String, List<OFStatistics>>> sliced;
	private HashMap<Long, HashMap<OFMatch, FSFWOFFlowStatisticsReply>> map;
	

	private FlowSpaceFirewall parent;
	
	public FlowStatCache(FlowSpaceFirewall parent){
		//this is the raw flowStats from the switch
		flowStats = new HashMap<Long, List<OFStatistics>>();
		//this is the raw portStat from the switch
		portStats = new HashMap<Long, HashMap<Short, OFStatistics>>();
		//this is the mapping from DPID OFMatch to FlowMod
		map = new HashMap<Long, HashMap<OFMatch, FSFWOFFlowStatisticsReply>>();
		//this is the results to be returned when requested
		sliced = new HashMap<Long, HashMap<String, List<OFStatistics>>>();
		//need one more to track the lastSeen time
		this.parent = parent;
	}
	
	
	//lets us write out object to disk
	public synchronized void writeObject(ObjectOutputStream aOutputStream) throws IOException{
		//need to clone it so that we can make changes while serializing
		aOutputStream.writeObject(sliced.clone());
		aOutputStream.writeObject(map.clone());
	}
	
	//lets us read our object from disk
	@SuppressWarnings("unchecked")
	public synchronized void readObject(ObjectInputStream aInputStream) throws IOException{
		HashMap<Long, HashMap<String, List<OFStatistics>>> cache;
		HashMap<Long,HashMap<OFMatch,FSFWOFFlowStatisticsReply>> tmpMap;
		try {
			cache = (HashMap<Long, HashMap<String, List<OFStatistics>>>) aInputStream.readObject();
			this.sliced = cache;

			
			
			tmpMap = (HashMap<Long,HashMap<OFMatch,FSFWOFFlowStatisticsReply>>) aInputStream.readObject();
			this.map = tmpMap;
			
			long time = System.currentTimeMillis();
			for(long dpid : this.sliced.keySet()){
				HashMap<String, List<OFStatistics>> sliceMap = this.sliced.get(dpid);
				for(String sliceName : sliceMap.keySet()){
					List<OFStatistics> stats = sliceMap.get(sliceName);
					for(OFStatistics stat: stats){
						FSFWOFFlowStatisticsReply flowStat = (FSFWOFFlowStatisticsReply)stat;
						flowStat.setLastSeen(time);
					}
				}
				
				HashMap<OFMatch,FSFWOFFlowStatisticsReply> switchMap = this.map.get(dpid);
				for(OFMatch match : switchMap.keySet()){
					FSFWOFFlowStatisticsReply stat = switchMap.get(match);
					stat.setLastSeen(time);
				}
			}
			
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			log.error("Error reading in cache file!  Starting from clean cache!");
			e.printStackTrace();
		}
	}
	
	public synchronized void delFlowMod(long dpid, String sliceName, OFFlowMod flow,List<OFFlowMod> flows){
		log.error("Deleting flow " + flow.toString());
		if(!map.containsKey(dpid)){
			log.debug("No map exists!");
			return;
		}
				
		HashMap<OFMatch, FSFWOFFlowStatisticsReply> flowMap = map.get(dpid);
		log.debug("Total Flows: " + flows.size());
		for(OFFlowMod sent_flow : flows){
			log.debug("attempting to delete flow we sent: " + sent_flow.toString());
			if(flowMap.containsKey(sent_flow.getMatch())){
				FSFWOFFlowStatisticsReply stat = flowMap.get(sent_flow.getMatch());
				if(stat.hasParent()){
					log.debug("Setting parent to deleted");
					stat.getParentStat().setToBeDeleted(true);
				}
				stat.setToBeDeleted(true);
				log.debug("Setting flow mod and parent to be deleted");
			}else{
				//already gone nothing to do!
				log.debug("Flow mod was not found could not be deleted");
			}
		}
		
		//belt and suspenders!
		//set the controller side of this just in case it exists but no actual flow stat did
		if(this.sliced.containsKey(dpid)){
			if(this.sliced.get(dpid).containsKey(sliceName)){
				List<OFStatistics> stats = this.sliced.get(dpid).get(sliceName);
				for(OFStatistics stat : stats){
					FSFWOFFlowStatisticsReply fsfwStat = (FSFWOFFlowStatisticsReply) stat;
					if(fsfwStat.getMatch().equals(flow.getMatch())){
						fsfwStat.setToBeDeleted(true);
					}
				}
			}
		}		
	}
	
	/**
	 * builds a flowStat from an OFFlowMOd
	 * @param flow
	 * @return FSFWOFFlowStatisticsReply
	 */
	private FSFWOFFlowStatisticsReply buildFlowStatFromFlowMod(OFFlowMod flow){
		FSFWOFFlowStatisticsReply flowStat = new FSFWOFFlowStatisticsReply();
		flowStat.setMatch(flow.getMatch());
		flowStat.setActions(flow.getActions());
		flowStat.setPacketCount(0);
		flowStat.setByteCount(0);
		flowStat.setPriority(flow.getPriority());
		flowStat.setCookie(flow.getCookie());
		flowStat.setHardTimeout(flow.getHardTimeout());
		flowStat.setIdleTimeout(flow.getIdleTimeout());
		short length = 0;
		for(OFAction act : flowStat.getActions()){
			length += act.getLengthU();
		}
		flowStat.setLength((short)(OFFlowStatisticsReply.MINIMUM_LENGTH + length));
		return flowStat;
	}
	
	/**
	 * adds a flowMod to the flowstat cache
	 * @param dpid
	 * @param sliceName
	 * @param flow
	 * @param flows
	 */
	
	public synchronized void addFlowMod(Long dpid, String sliceName, OFFlowMod flow, List<OFFlowMod> flows){
		//create a flow stat reply and set the cache to it
		FSFWOFFlowStatisticsReply flowStat = buildFlowStatFromFlowMod(flow);
		flowStat.setSliceName(sliceName);
		
		if(sliced.containsKey(dpid)){
			HashMap<String, List<OFStatistics>> sliceStats = sliced.get(dpid);
			if(sliceStats.containsKey(sliceName)){
				log.debug("Adding Flow to the cache!");
				sliceStats.get(sliceName).add(flowStat);
				log.debug("sliced stats size: " + sliceStats.get(sliceName).size());
			}else{
				List<OFStatistics> stats = new ArrayList<OFStatistics>();
				log.debug("Adding flow to the cache! Created the Slice hash");
				stats.add(flowStat);		
				sliceStats.put(sliceName, stats);
			}
			
		}else{
			HashMap<String, List<OFStatistics>> sliceStats = new HashMap<String, List<OFStatistics>>();
			List<OFStatistics> stats = new ArrayList<OFStatistics>();
			sliceStats.put(sliceName, stats);
			stats.add(flowStat);
			sliced.put(dpid, sliceStats);
		}
		//need to update last seen
		log.debug("Added Flow: " + flowStat.toString() + " to cache!");
		flowStat.setLastSeen(System.currentTimeMillis());
		
		if(!map.containsKey(dpid)){
			HashMap<OFMatch, FSFWOFFlowStatisticsReply> tmpHash = new HashMap<OFMatch, FSFWOFFlowStatisticsReply>();
			map.put(dpid, tmpHash);
		}
		
		HashMap<OFMatch, FSFWOFFlowStatisticsReply> switchMap = map.get(dpid);
		for(OFFlowMod sent_flow : flows){
			FSFWOFFlowStatisticsReply sentFlowStat = buildFlowStatFromFlowMod(sent_flow);
			sentFlowStat.setSliceName(sliceName);
			sentFlowStat.setParentStat(flowStat);
			sentFlowStat.setLastSeen(System.currentTimeMillis());
			switchMap.put(sentFlowStat.getMatch(), sentFlowStat);
		}
	}	
	
	
	public List <IOFSwitch> getSwitches(){
		return this.parent.getSwitches();
	}
	
	
	/**
	 * clearFlowCache this just clears the flow cache from the switch
	 * it does not clear the controller view of the cache
	 * @param switchId
	 */
	
	public synchronized void clearFlowCache(Long switchId){
		flowStats.remove(switchId);
		
		if(this.sliced.containsKey(switchId)){
			HashMap<String, List<OFStatistics>> sliceStats = this.sliced.get(switchId);
			Iterator<String> it = sliceStats.keySet().iterator();
			while(it.hasNext()){
				String slice = (String)it.next();
				List<OFStatistics> ofStats = this.sliced.get(switchId).get(slice);
				Iterator<OFStatistics> itStat = ofStats.iterator();
				while(itStat.hasNext()){
					OFStatistics stat = (OFStatistics)itStat.next();
					FSFWOFFlowStatisticsReply flowStat = (FSFWOFFlowStatisticsReply)stat;
					flowStat.setVerified(false);
					flowStat.setLastSeen(System.currentTimeMillis());
				}
			}
		}
		
		if(this.map.containsKey(switchId)){
			HashMap<OFMatch, FSFWOFFlowStatisticsReply> flowMap = this.map.get(switchId);
			Iterator<OFMatch> it = flowMap.keySet().iterator();
			while(it.hasNext()){
				FSFWOFFlowStatisticsReply stat = flowMap.get(it.next());
				stat.setVerified(false);
				stat.setLastSeen(System.currentTimeMillis());
			}
		}
		
	}
	
	/**
	 * just updates the data in the flowstat
	 * @param cachedStat
	 * @param newStat
	 */
	
	private boolean updateFlowStatData(OFStatistics cachedStat, OFFlowStatisticsReply newStat, HashMap<String, Integer> flowCount){
		
		FSFWOFFlowStatisticsReply cachedFlowStat = (FSFWOFFlowStatisticsReply) cachedStat;
		if(cachedFlowStat.toBeDeleted()){
			//its going to be deleted don't update
			return false;
		}

		//update the data
		cachedFlowStat.setByteCount(cachedFlowStat.getByteCount() + newStat.getByteCount());
		cachedFlowStat.setPacketCount(cachedFlowStat.getPacketCount() + newStat.getPacketCount());
		cachedFlowStat.setDurationNanoseconds(newStat.getDurationNanoseconds());
		cachedFlowStat.setDurationSeconds(newStat.getDurationSeconds());
		cachedFlowStat.setLastSeen(System.currentTimeMillis());
		cachedFlowStat.setVerified(true);
		
		String sliceName = ((FSFWOFFlowStatisticsReply) cachedStat).getSliceName();
		if(cachedFlowStat.hasParent()){
			if(flowCount.containsKey(sliceName)){
				flowCount.put(sliceName, (flowCount.get(sliceName) + 1));
			}else{
				flowCount.put(sliceName,1);
			}
		
			//update the parent cache
			FSFWOFFlowStatisticsReply parentStat = cachedFlowStat.getParentStat();
			updateFlowStatData(parentStat,newStat,flowCount);
		}
				
		return true;
	}
	
	
	/**
	 * buildFlowMod - builds a FlowMod based on a flowStat
	 * @param flowStat
	 * @return OFFlowMod
	 */
	private OFFlowMod buildFlowMod(OFFlowStatisticsReply flowStat){
		OFFlowMod flowMod = new OFFlowMod();
		flowMod.setMatch(flowStat.getMatch().clone());
		flowMod.setActions(flowStat.getActions());
		flowMod.setPriority(flowStat.getPriority());
		flowMod.setCookie(flowStat.getCookie());
		flowMod.setIdleTimeout(flowStat.getIdleTimeout());
		flowMod.setHardTimeout(flowStat.getHardTimeout());
		return flowMod;
	}
	
	/**
	 * findSliceForFlow - finds a slice based on the flow rule passed in
	 * @param switchId
	 * @param flowMod
	 * @return Slicer
	 */
	private Slicer findSliceForFlow(long switchId, OFFlowMod flowMod){
		List<HashMap<Long, Slicer>> slices = parent.getSlices();
		for(HashMap<Long,Slicer> tmpSlices : slices){
			if(!tmpSlices.containsKey(switchId)){
				//switch not part of this slice
				log.debug("Switch is not part of this slice!");
				continue;
			}
	
			Slicer slice = tmpSlices.get(switchId);
			log.debug("Looking at slice: " + slice.getSliceName());
			List<OFFlowMod> flows = slice.allowedFlows(flowMod);
			if(flows.size() > 0){
				return slice;
			}
		}
		return null;
	}
	
	private void deleteFlow(Long switchId, OFFlowStatisticsReply flowStat){
		List<IOFSwitch> switches = this.getSwitches();
		for(IOFSwitch sw : switches){
			if(sw.getId() == switchId){
				OFFlowMod flowMod = new OFFlowMod();
				flowMod.setMatch(flowStat.getMatch().clone());
				flowMod.setIdleTimeout(flowStat.getIdleTimeout());
				flowMod.setHardTimeout(flowStat.getHardTimeout());
				flowMod.setCookie(flowStat.getCookie());
				flowMod.setPriority(flowStat.getPriority());
				flowMod.setCommand(OFFlowMod.OFPFC_DELETE_STRICT);
				flowMod.setLengthU(OFFlowMod.MINIMUM_LENGTH);
				flowMod.setXid(sw.getNextTransactionId());

				List<OFMessage> msgs = new ArrayList<OFMessage>();
				msgs.add((OFMessage)flowMod);
				
				try {
					sw.write(msgs, null);
				} catch (IOException e) {
					log.error("Error attempting to send flow delete for flow that fits in NO flowspace");
					e.printStackTrace();
				}
			}
		}
	}
	
	/**
	 * processFlow - processes a FlowStat and updates or removes the flow
	 * @param switchId
	 * @param flowStat
	 * @param time
	 * @param flowCount
	 */
	
	private void processFlow(Long switchId, OFFlowStatisticsReply flowStat, long time, HashMap<String, Integer> flowCount){
		
		if(!map.containsKey(switchId)){
			HashMap<OFMatch, FSFWOFFlowStatisticsReply> tmpMap = new HashMap<OFMatch, FSFWOFFlowStatisticsReply>();
			map.put(switchId, tmpMap);
		}
		
		HashMap<OFMatch, FSFWOFFlowStatisticsReply> flowMap = map.get(switchId);
		
		if(flowMap.containsKey(flowStat.getMatch())){
			//found our match in the expected stats
			log.debug("Found the flow rule in our mapping");
			FSFWOFFlowStatisticsReply cachedStat = (FSFWOFFlowStatisticsReply) flowMap.get(flowStat.getMatch());

			//if the actions match we are good to update
			if(cachedStat.compareActions(flowStat.getActions())){
				if(this.updateFlowStatData(cachedStat, flowStat, flowCount)){
					return;
				}else{	
					//uh oh this was set to be deleted...
					log.error("I just tried to update a flow I thought was deleted!!!");
					return;
				}
			}else{
				//well crap the actions don't line up...
				//delete this and its parent and siblings from the cache
				//then add it
				log.error("Flow Actions do not match what we have in cache!");
				log.error(cachedStat.toString());
				log.error(flowStat.toString());
				FSFWOFFlowStatisticsReply parentStat = cachedStat.getParentStat();
				OFFlowMod flow = this.buildFlowMod(parentStat);
				Slicer slice = this.parent.getProxy(switchId, parentStat.getSliceName()).getSlicer();
				if(slice == null){
					//uh ok so this flow is not a part of any slice
					//kind of a convoluted situation here
					//delete the flow
					this.deleteFlow(switchId, parentStat);
					return;
				}
				//get a list of all flows that this will invalidate
				List<OFFlowMod> flows;
				if(slice.getTagManagement()){
					log.error("Managed Flows");
					flows = slice.managedFlows(flow);
				}else{
					log.error("Allowed Flows!'");
					flows = slice.allowedFlows(flow);
				}
				this.delFlowMod(switchId, slice.getSliceName(),flow, flows);
			}
		}else{
			//ok so our flow match didn't even show up
			//need to first slice it and figure out where it belongs
			//if it does fit in our slice then add it otherwise delete
			
			OFFlowMod flow = this.buildFlowMod(flowStat);
			Slicer slice = this.findSliceForFlow(switchId, flow);
			
			//check to see if it is the default drop rule
			if(flow.getMatch().match(new OFMatch())){
				if(flow.getActions().size() == 0){
					//default drop rule... do nothing
					return;
				}
			}
			
			//if it doesn't fit into any slice we need to remove it!
			if(slice == null){
				log.info("Error finding/adding flow stat to the cache!  This flow is not a part of any Slice!" + flowStat.toString());
				//remove flow
				this.deleteFlow(switchId,flowStat);
			}else{
				//woohoo we sliced it!
				//check to see if we are in managed tag mode
				List<OFFlowMod> flows = new ArrayList<OFFlowMod>();
				if(slice.getTagManagement()){
					//we are in managed tag mode need to wildcard the dl-vlan
					flows.add(flow);
					OFFlowMod newFlow;
					try {
						newFlow = flow.clone();
						newFlow.getMatch().setDataLayerVirtualLan((short)0);
						newFlow.getMatch().setWildcards(newFlow.getMatch().getWildcardObj().wildcard(Wildcards.Flag.DL_VLAN));
						List<OFAction> newActions = new ArrayList<OFAction>();
						int length = 0;
						for(OFAction act : newFlow.getActions()){
							if(act.getType() == OFActionType.SET_VLAN_ID){
								//remove the SET_VLAN_VID
								continue;
							}else if(act.getType() == OFActionType.STRIP_VLAN){
								//remove the strip_VLAN action
								continue;
							}else{
								newActions.add(act);
								length += act.getLength();
							}
						}
						newFlow.setActions(newActions);
						newFlow.setLength((short)(OFFlowMod.MINIMUM_LENGTH + length));
						this.addFlowMod(switchId, slice.getSliceName(), newFlow, flows);
					} catch (CloneNotSupportedException e) {
						log.warn("Unable to clone flowMod!");
						return;
					}
				}else{
					flows.add(flow);
					this.addFlowMod(switchId, slice.getSliceName(), flow, flows);
				}
				//ok we added it to our cache now update the flows
				FSFWOFFlowStatisticsReply cachedStat = (FSFWOFFlowStatisticsReply) flowMap.get(flowStat.getMatch());
				if(this.updateFlowStatData(cachedStat, flowStat, flowCount)){
					return;
				}else{
					log.warn("error adding a flow we didn't expect to the cache and then updating it");
				}
			}
		}
	}
	
	/**
	 * setFlowCache
	 * sets the stats for the given switch
	 * @param switchId
	 * @param stats
	 */
	public synchronized void setFlowCache(Long switchId, List <OFStatistics> stats){
		flowStats.put(switchId, stats);
		log.debug("Setting Flow Cache! Switch: " + switchId + " Total Stats: " + stats.size());
		
		//first thing is to set all counters for all stats to 0
		if(this.sliced.containsKey(switchId)){
			//loop through our current cache and set all packet/byte counts to 0
			Iterator<String> it = this.sliced.get(switchId).keySet().iterator();
			while(it.hasNext()){
				String slice = (String)it.next();
				List<OFStatistics> ofStats = this.sliced.get(switchId).get(slice);
				for(OFStatistics stat : ofStats){
					OFFlowStatisticsReply flowStat = (OFFlowStatisticsReply) stat;
					flowStat.setByteCount(0);
					flowStat.setPacketCount(0);
				}
			}
		}
		
		HashMap <String, Integer> flowCounts = new HashMap<String, Integer>();
		//now update process all the flows find their mapping and cache them
		long time = System.currentTimeMillis();
		//loop through all stats
		for(OFStatistics stat : stats){
			OFFlowStatisticsReply flowStat = (OFFlowStatisticsReply) stat;
			log.debug("Processing Flow: " + flowStat.toString());
			this.processFlow(switchId, flowStat, time, flowCounts);
		}
		
		//are there any flows that need to go away (ie... we didn't see them since the last poll cycle)		
		long timeToRemove = time - 60000;
		if(this.sliced.containsKey(switchId)){
			HashMap<String, List<OFStatistics>> sliceStats = this.sliced.get(switchId);
			Iterator<String> it = sliceStats.keySet().iterator();
			while(it.hasNext()){
				String slice = (String)it.next();
				List<OFStatistics> ofStats = this.sliced.get(switchId).get(slice);
				Iterator<OFStatistics> itStat = ofStats.iterator();
				while(itStat.hasNext()){
					OFStatistics stat = (OFStatistics)itStat.next();
					FSFWOFFlowStatisticsReply flowStat = (FSFWOFFlowStatisticsReply)stat;
					if(flowStat.lastSeen() < timeToRemove){
						log.debug("Removing flowStat: " + stat.toString());
						itStat.remove();
							//have to also find all flows that point to this flow :(
						this.removeMappedCache(switchId, flowStat);
					}else if(flowStat.toBeDeleted()){
						itStat.remove();
						this.removeMappedCache(switchId, flowStat);
					}
				}
			}
		}
		
		if(this.map.containsKey(switchId)){
			HashMap<OFMatch, FSFWOFFlowStatisticsReply> flowMap = this.map.get(switchId);
			Iterator<OFMatch> it = flowMap.keySet().iterator();
			while(it.hasNext()){
				FSFWOFFlowStatisticsReply stat = flowMap.get(it.next());
				if(stat.lastSeen() < timeToRemove){
					log.debug("Removing mapping flowStat: " + stat.toString());
					it.remove();
				}else if(stat.toBeDeleted()){
					it.remove();
				}
			}
		}
		
		//update all proxies for this switch so that they have the proper flow count
		//ISSUE=10641
		List<HashMap<Long, Slicer>> slices = new ArrayList<HashMap<Long,Slicer>>(parent.getSlices());

		for(HashMap<Long,Slicer> tmpSlices : slices){
			if(!tmpSlices.containsKey(switchId)){
				//switch not part of this slice
				continue;
			}
			Proxy p = this.parent.getProxy(switchId, tmpSlices.get(switchId).getSliceName());
			if(p == null){
				continue;
			}
			if(flowCounts.containsKey(p.getSlicer().getSliceName())){
				p.setFlowCount(flowCounts.get(p.getSlicer().getSliceName()));
			}else{
				log.error("Problem updating flow counts for slice: " + p.getSlicer().getSwitchName() + ":" + p.getSlicer().getSliceName());
			}
		}
	}
	
	/**
	 * removeMappedCache
	 * @param switchId
	 * @param stat
	 * 
	 * removes the flows that are mapped to this stats
	 * 
	 */
	
	private void removeMappedCache(long switchId, OFStatistics stat){
		if(map.containsKey(switchId)){
			HashMap<OFMatch, FSFWOFFlowStatisticsReply> switchMap = map.get(switchId);
			//well crap no easy way to do this...
			Iterator<Entry<OFMatch, FSFWOFFlowStatisticsReply>> it = switchMap.entrySet().iterator();
			while(it.hasNext()){
				Entry<OFMatch, FSFWOFFlowStatisticsReply> entry = (Entry<OFMatch, FSFWOFFlowStatisticsReply>) it.next();
				if(entry.getValue().hasParent()){
					if(entry.getValue().getParentStat().equals(stat)){
						it.remove();
					}
				}
			}
			
		}
	}
	
	/**
	 * tries to find expired flows and then signals that they need to be removed!
	 * @param switchId
	 * @return
	 */
	
	public synchronized List<FlowTimeout> getPossibleExpiredFlows(Long switchId){
		List<FlowTimeout> flowTimeouts = new ArrayList<FlowTimeout>();
		List<HashMap<Long, Slicer>> slices = new ArrayList<HashMap<Long,Slicer>>(parent.getSlices());

		for(HashMap<Long,Slicer> tmpSlices : slices){
			if(!tmpSlices.containsKey(switchId)){
				//switch not part of this slice
				continue;
			}
			Proxy proxy = this.parent.getProxy(switchId, tmpSlices.get(switchId).getSliceName());
			if(proxy == null){
				return flowTimeouts;
			}
			flowTimeouts.addAll( proxy.getTimeouts());
		}
			
		return flowTimeouts;
	}
	
	/**
	 * check for expired flows
	 * @param switchId
	 */
	
	public void checkExpireFlows(Long switchId){
		List<HashMap<Long, Slicer>> slices = new ArrayList<HashMap<Long,Slicer>>(parent.getSlices());

		for(HashMap<Long,Slicer> tmpSlices : slices){
			if(!tmpSlices.containsKey(switchId)){
				//switch not part of this slice
				continue;
			}
			Proxy p = this.parent.getProxy(switchId, tmpSlices.get(switchId).getSliceName());
			if(p == null){
				return;
			}
			p.checkExpiredFlows();
		}
	}
	
	
	/**
	 * retrieves the stats for the requested switch
	 * @param switchId
	 * @return
	 */
	public synchronized List <OFStatistics> getSwitchFlowStats(Long switchId){
		log.debug("Looking for switch stats: " + switchId);
		return flowStats.get(switchId);
	}
	

	public synchronized List <OFStatistics> getSlicedFlowStats(Long switchId, String sliceName){
		log.debug("Getting sliced stats for switch: " + switchId + " and slice " + sliceName);
		if(!flowStats.containsKey(switchId)){
			return null;
		}
		if(sliced.containsKey(switchId)){
			HashMap<String, List<OFStatistics>> tmpStats = sliced.get(switchId);
			if(tmpStats.containsKey(sliceName)){				
				//create a copy of the array so we can manipulate it
				List<OFStatistics> stats = new ArrayList<OFStatistics>(tmpStats.get(sliceName));

				//we only want verified flows to appear
				Iterator<OFStatistics> it = stats.iterator();
				while(it.hasNext()){
					FSFWOFFlowStatisticsReply flowStat = (FSFWOFFlowStatisticsReply)it.next();
					if(flowStat.toBeDeleted() || !flowStat.isVerified()){
						it.remove();
					}
				}
				
				log.debug("Returning " + stats.size() + " flow stats");
				return stats;
			}
			log.debug("Switch cache has no slice cache named: " + sliceName);
			return new ArrayList<OFStatistics>();
		}
		log.debug("Switch cache does not even exist");
		return new ArrayList<OFStatistics>();
	}
	
	
	public synchronized void setPortCache(Long switchId, HashMap<Short, OFStatistics> stats){
		portStats.put(switchId, stats);
	}
	
	public synchronized OFStatistics getPortStats(Long switchId, short portId){
		HashMap<Short, OFStatistics> nodeStats = portStats.get(switchId);
		return nodeStats.get(portId);
	}
	
	public synchronized HashMap<Short, OFStatistics> getPortStats(Long switchId){
		return portStats.get(switchId);
	}
	
	
}
