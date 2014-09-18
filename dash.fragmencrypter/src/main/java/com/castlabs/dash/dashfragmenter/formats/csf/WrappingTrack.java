package com.castlabs.dash.dashfragmenter.formats.csf;

import com.coremedia.iso.boxes.CompositionTimeToSample;
import com.coremedia.iso.boxes.SampleDependencyTypeBox;
import com.coremedia.iso.boxes.SampleDescriptionBox;
import com.coremedia.iso.boxes.SubSampleInformationBox;
import com.googlecode.mp4parser.authoring.Edit;
import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.TrackMetaData;

import java.io.IOException;
import java.util.List;

/**
 * A simple track wrapper that delegates all calls to parent track. Override certain methods inline to change result.
 */
public class WrappingTrack implements Track {
    Track parent;
    public WrappingTrack(Track parent) {
        this.parent = parent;
    }

    public SampleDescriptionBox getSampleDescriptionBox() {
        return parent.getSampleDescriptionBox();
    }

    public long[] getSampleDurations() {
        return parent.getSampleDurations();
    }

    public long getDuration() {
        return parent.getDuration();
    }

    public List<CompositionTimeToSample.Entry> getCompositionTimeEntries() {
        return parent.getCompositionTimeEntries();
    }

    public long[] getSyncSamples() {
        return parent.getSyncSamples();
    }

    public List<SampleDependencyTypeBox.Entry> getSampleDependencies() {
        return parent.getSampleDependencies();
    }

    public TrackMetaData getTrackMetaData() {
        return parent.getTrackMetaData();
    }

    public String getHandler() {
        return parent.getHandler();
    }

    public List<Sample> getSamples() {
        return parent.getSamples();
    }

    public SubSampleInformationBox getSubsampleInformationBox() {
        return parent.getSubsampleInformationBox();
    }

    public String getName() {
        return parent.getName() + "'";
    }

    public List<Edit> getEdits() {
        return parent.getEdits();
    }

    public void close() throws IOException {
        parent.close();
    }
}
