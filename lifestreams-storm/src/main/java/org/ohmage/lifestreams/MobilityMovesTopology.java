package org.ohmage.lifestreams;
import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.Days;
import org.ohmage.lifestreams.models.data.MobilityData;
import org.ohmage.lifestreams.models.data.MovesCredentials;
import org.ohmage.lifestreams.spouts.MovesSpout;
import org.ohmage.lifestreams.spouts.OhmageStreamSpout;
import org.ohmage.lifestreams.tasks.GeoDiameterTask;
import org.ohmage.lifestreams.tasks.mobility.HMMMobilityRectifier;
import org.ohmage.lifestreams.tasks.mobility.MobilityActivitySummarizer;
import org.ohmage.lifestreams.tasks.mobility.TimeLeaveReturnHome;
import org.ohmage.lifestreams.tasks.moves.ActivitySummarizer;
import org.ohmage.lifestreams.tasks.moves.FilterDuplicatedSegment;
import org.ohmage.lifestreams.tasks.moves.TrackPointExtractor;
import org.ohmage.lifestreams.utils.KryoSerializer;
import org.ohmage.lifestreams.utils.SimpleTopologyBuilder;
import org.ohmage.models.OhmageStream;
import org.ohmage.models.OhmageUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.nutrino.api.moves.impl.service.MovesSecurityManager;
import backtype.storm.Config;
import backtype.storm.LocalCluster;


@Component
public class MobilityMovesTopology {

	@Autowired
	OhmageUser requester;
	@Autowired
	DateTime since;
	
	@Autowired
	OhmageStream mobilityStream;
	@Autowired
	OhmageStream activitySummaryStream;
	@Autowired
	OhmageStream movesCredentialStream;
	@Autowired
	OhmageStream geodiameterStream;
	@Autowired
	OhmageStream leaveArriveHomeStream;
	@Autowired
	OhmageStream movesSegmentStream;
	@Autowired
	MovesSecurityManager movesSecurityManger;
	 
	@Value("${parallelism.per.mobility.task}")
	int parallelismPerTask;

	@Value("${mobility.spout.number}")
	int mobility_spout_number;
	
	@Value("${enable.mobility.topology}")
	boolean enableMobility;
	
	@Value("${enable.moves.topology}")
	boolean enableMoves;
	
	public void run(List<OhmageUser> requestees){
		
		OhmageStreamSpout<MobilityData> mobilitySpout = new OhmageStreamSpout<MobilityData>(
				mobilityStream, requester, requestees, since, MobilityData.class, null, true, "wifi_data,mode");
		MovesSpout movesSpout = new MovesSpout(movesCredentialStream, requester, requestees, new DateTime("2014-2-12"), movesSecurityManger);

		/** setup the topology **/
		SimpleTopologyBuilder builder = new SimpleTopologyBuilder();

		
		/** Topology part 1. create a spout that gets mobility data and the tasks that consume the data **/
		if(enableMobility){
			builder.setSpout("MobilityDataStream", mobilitySpout, mobility_spout_number);
			
			builder.setTask("PlaceDetection", new TimeLeaveReturnHome(), "MobilityDataStream")
						.setParallelismHint(parallelismPerTask)
						.setTimeWindowSize(Days.ONE)
						.setTargetStream(leaveArriveHomeStream);
	
			// compute daily geodiameter from Mobility data
			builder.setTask("GeoDistanceBolt", new GeoDiameterTask(), "MobilityDataStream")
						.setTargetStream(geodiameterStream)
						.setParallelismHint(parallelismPerTask)
						.setTimeWindowSize(Days.ONE);
			
			// HMM model to correct shor-term errors in Mobility
			builder.setTask("HMMMobilityStateRectifier", new HMMMobilityRectifier(), "MobilityDataStream")
						.setParallelismHint(parallelismPerTask)
						.setTimeWindowSize(Days.ONE);
			// based on the corrected Mobility data, compute daily activity summary
			builder.setTask("MobilityActivitySummarier", new MobilityActivitySummarizer(), "HMMMobilityStateRectifier")
						.setParallelismHint(parallelismPerTask)
						.setTargetStream(activitySummaryStream)
						.setTimeWindowSize(Days.ONE);
		}

		/** Topology part 2. create a spout that gets Moves data and the tasks that consume the data **/
		if(enableMoves){
			builder.setSpout("RawMovesDataStream", movesSpout, 1);
			
			// segments from the ohmage or the local Moves fetcher may contain duplication. Filter them out.
			builder.setTask("MovesDataStream", new FilterDuplicatedSegment(), "RawMovesDataStream")
				.setTimeWindowSize(Days.ONE)
				.setTargetStream(movesSegmentStream);
			
			// extract track points from moves segments
			builder.setTask("MovesTrackPointExtractor", new TrackPointExtractor(), "MovesDataStream")
					.setTimeWindowSize(Days.ONE);
			
			
			// compute geo diameter based on the track points
			builder.setTask("MovesGeoDiameter", new GeoDiameterTask(), "MovesTrackPointExtractor")
					.setTargetStream(geodiameterStream)
					.setTimeWindowSize(Days.ONE);
			
			// generate daily activity summary
			builder.setTask("MovesActivitySummarier", new ActivitySummarizer(), "MovesDataStream")
					.setTargetStream(activitySummaryStream)
					.setTimeWindowSize(Days.ONE);
			
			builder.setTask("TimeLeaveReturnHome", new org.ohmage.lifestreams.tasks.moves.TimeLeaveReturnHome(), "MovesDataStream")
					.setTargetStream(leaveArriveHomeStream)
					.setTimeWindowSize(Days.ONE);
		}
		
		Config conf = new Config();
		conf.setDebug(false);
		
		// if it is a dryrun? if so, no data will be writeback to ohmage
		conf.put(LifestreamsConfig.DRYRUN_WITHOUT_UPLOADING, true);
		// keep the computation states in a local database or not.
		conf.put(LifestreamsConfig.OUTPUT_TO_LOCAL_REDIS, true);
		// keep the computation states in a local database or not.
		conf.put(LifestreamsConfig.ENABLE_STATEFUL_FUNCTION, false);
		// Since it may require very long time for a tuple to be fully processed, we make the tuples never timeout.
		conf.put(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS , Integer.MAX_VALUE);
		// register all the classes used in Lifestreams framework to the kryo serializer
		KryoSerializer.setRegistrationsForStormConfig(conf);


		LocalCluster cluster = new LocalCluster();
		cluster.submitTopology("Lifestreams-on-storm", conf, builder.createTopology());
		
		while (true){
			try {
				Thread.sleep(100000000);
			} catch (InterruptedException e) {
				return;
			}
		}


		
		
	}
}
