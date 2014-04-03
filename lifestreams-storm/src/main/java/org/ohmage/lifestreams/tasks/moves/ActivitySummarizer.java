package org.ohmage.lifestreams.tasks.moves;

import java.util.ArrayList;

import org.joda.time.Interval;
import org.ohmage.lifestreams.bolts.TimeWindow;
import org.ohmage.lifestreams.models.MobilityState;
import org.ohmage.lifestreams.models.StreamRecord;
import org.ohmage.lifestreams.models.data.ActivityEpisode;
import org.ohmage.lifestreams.models.data.ActivitySummaryData;
import org.ohmage.lifestreams.tasks.SimpleTask;

import co.nutrino.api.moves.impl.dto.activity.MovesActivity;
import co.nutrino.api.moves.impl.dto.activity.MovesActivityEnum;
import co.nutrino.api.moves.impl.dto.storyline.MovesSegment;

/**
 * @author changun This task generates a activity summary (see
 *         ActivitySummaryData) from the Moves segments in the time window
 *         (e.g. daily).
 * 
 */
public class ActivitySummarizer extends SimpleTask<MovesSegment> {
	ArrayList<MovesSegment> segments = new ArrayList<MovesSegment>();

	@Override
	public void executeDataPoint(StreamRecord<MovesSegment> dp,	TimeWindow window) {
		// srore the Moves segment in the buffer
		segments.add(dp.d());
	}

	// this function computes the activity summary from the Moves segment we have received so far.
	private void computeAndEmitSummary(TimeWindow window, boolean isSnapshot) {
		double totalActiveTime = 0;
		double totalTime = 0;
		double totalTransportationTime = 0;
		ArrayList<ActivityEpisode> activityEpisodes = new ArrayList<ActivityEpisode>();
		for (MovesSegment segment : segments) {
			// go over each segments in the time window (usually daily)

			// accumulate total time in seconds
			totalTime += new Interval(segment.getStartTime(),
					segment.getEndTime()).toDurationMillis() / 1000;
			if (segment.getActivities() != null) {
				// if there is activity in this segment
				for (MovesActivity activity : segment.getActivities()) {
					if(activity.getGroup() == null){
						this.getLogger().error("Encourter a activity without group: {}. Assign it to walk for now.", activity.getActivity());
						activity.setGroup(MovesActivityEnum.Walking);
					}
					// go over each activity
					MobilityState state = MobilityState
							.fromMovesActivity(activity.getGroup());
					if (state.isActive()) {
						// accumulate active times, and generate activity
						// instance
						totalActiveTime += activity.getDuration();
						// create activity instance
						activityEpisodes.add(ActivityEpisode
								.forMovesActivity(activity));
					} else {
						if (state.equals(MobilityState.DRIVE)) {
							// accumulate transport time
							totalTransportationTime += activity.getDuration();
						}
					}
				}
			}
		}
		// create data point
		ActivitySummaryData data = new ActivitySummaryData(window, this)
				.setTotalActiveTimeInSeconds(totalActiveTime)
				.setTotalSedentaryTimeInSeconds(totalTime - totalActiveTime)
				.setTotalTimeInSeconds(totalTime)
				.setTotalTransportationTimeInSeconds(totalTransportationTime)
				.setActivityEpisodes(activityEpisodes);
		// create and emit the record
		this.createRecord()
					.setData(data)
					.setTimestamp(window.getFirstInstant())
					.setIsSnapshot(isSnapshot)
					.emit();
	}

	@Override
	public void finishWindow(TimeWindow window) {
		computeAndEmitSummary(window, false);
		// clear the segements for this timewindow
		segments.clear();
	}

	@Override
	public void snapshotWindow(TimeWindow window) {
		computeAndEmitSummary(window, true);

	}

}
