package flutter.tflite_audio;

import android.util.Log;
import android.util.Pair;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Reads in results from an instantaneous audio recognition model and smoothes them over time. */
public class LabelSmoothing {
    // Configuration settings.
    private List<String> labels = new ArrayList<String>();
    private long averageWindowDurationMs;
    private float detectionThreshold;
    private int suppressionMs;
    private long minimumTimeBetweenSamplesMs;

    // Working variables.
    private Deque<Pair<Long, float[]>> previousResults = new ArrayDeque<Pair<Long, float[]>>();
    private String previousTopLabel;
    private int labelsCount;
    private long previousTopLabelTime;
    private float previousTopLabelScore;

    private static final String SILENCE_LABEL = "_silence_";
    private static final long MINIMUM_TIME_FRACTION = 4;

    public LabelSmoothing(
            List<String> inLabels,
            long inAverageWindowDurationMs,
            float inDetectionThreshold,
            int inSuppressionMS,
            long inMinimumTimeBetweenSamplesMS) {
        labels = inLabels;
        averageWindowDurationMs = inAverageWindowDurationMs;
        detectionThreshold = inDetectionThreshold;
        suppressionMs = inSuppressionMS;
        labelsCount = inLabels.size();
        previousTopLabel = SILENCE_LABEL;
        previousTopLabelTime = Long.MIN_VALUE;
        previousTopLabelScore = 0.0f;
        minimumTimeBetweenSamplesMs = inMinimumTimeBetweenSamplesMS;
    }

    /** Holds information about what's been recognized. */
    public static class RecognitionResult {
        public final String foundCommand;
        public final float score;
        public final boolean isNewCommand;

        public RecognitionResult(String inFoundCommand, float inScore, boolean inIsNewCommand) {
            foundCommand = inFoundCommand;
            score = inScore;
            isNewCommand = inIsNewCommand;
        }
    }

    private static class ScoreForSorting implements Comparable<ScoreForSorting> {
        public final float score;
        public final int index;

        public ScoreForSorting(float inScore, int inIndex) {
            score = inScore;
            index = inIndex;
        }

        @Override
        public int compareTo(ScoreForSorting other) {
            if (this.score > other.score) {
                return -1;
            } else if (this.score < other.score) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    public RecognitionResult processLatestResults(float[] currentResults, long currentTimeMS) {
        if (currentResults.length != labelsCount) {
            throw new RuntimeException(
                    "The results for recognition should contain "
                            + labelsCount
                            + " elements, but there are "
                            + currentResults.length);
        }

        if ((!previousResults.isEmpty()) && (currentTimeMS < previousResults.getFirst().first)) {
            throw new RuntimeException(
                    "You must feed results in increasing time order, but received a timestamp of "
                            + currentTimeMS
                            + " that was earlier than the previous one of "
                            + previousResults.getFirst().first);
        }

        int howManyResults = previousResults.size();
        // Ignore any results that are coming in too frequently.
        if (howManyResults > 1) {
            final long timeSinceMostRecent = currentTimeMS - previousResults.getLast().first;
            if (timeSinceMostRecent < minimumTimeBetweenSamplesMs) {
                return new RecognitionResult(previousTopLabel, previousTopLabelScore, false);
            }
        }

        // Add the latest results to the head of the queue.
        previousResults.addLast(new Pair<Long, float[]>(currentTimeMS, currentResults));

        // Prune any earlier results that are too old for the averaging window.
        final long timeLimit = currentTimeMS - averageWindowDurationMs;
        while (previousResults.getFirst().first < timeLimit) {
            previousResults.removeFirst();
        }

        howManyResults = previousResults.size();

        // Calculate the average score across all the results in the window.
        float[] averageScores = new float[labelsCount];
        for (Pair<Long, float[]> previousResult : previousResults) {
            final float[] scoresTensor = previousResult.second;
            int i = 0;
            while (i < scoresTensor.length) {
                averageScores[i] += scoresTensor[i] / howManyResults;
                ++i;
            }
        }

        // Sort the averaged results in descending score order.
        ScoreForSorting[] sortedAverageScores = new ScoreForSorting[labelsCount];
        for (int i = 0; i < labelsCount; ++i) {
            sortedAverageScores[i] = new ScoreForSorting(averageScores[i], i);
        }
        Arrays.sort(sortedAverageScores);

        // See if the latest top score is enough to trigger a detection.
        final int currentTopIndex = sortedAverageScores[0].index;
        final String currentTopLabel = labels.get(currentTopIndex);
        final float currentTopScore = sortedAverageScores[0].score;
        // If we've recently had another label trigger, assume one that occurs too
        // soon afterwards is a bad result.
        long timeSinceLastTop;
        if (previousTopLabel.equals(SILENCE_LABEL) || (previousTopLabelTime == Long.MIN_VALUE)) {
            timeSinceLastTop = Long.MAX_VALUE;
        } else {
            timeSinceLastTop = currentTimeMS - previousTopLabelTime;
        }
        boolean isNewCommand;
        if ((currentTopScore > detectionThreshold) && (timeSinceLastTop > suppressionMs)) {
            previousTopLabel = currentTopLabel;
            previousTopLabelTime = currentTimeMS;
            previousTopLabelScore = currentTopScore;
            isNewCommand = true;
        } else {
            isNewCommand = false;
        }
        return new RecognitionResult(currentTopLabel, currentTopScore, isNewCommand);
    }
}