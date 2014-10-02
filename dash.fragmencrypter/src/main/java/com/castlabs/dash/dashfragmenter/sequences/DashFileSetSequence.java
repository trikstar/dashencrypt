package com.castlabs.dash.dashfragmenter.sequences;

import com.castlabs.dash.dashfragmenter.ExitCodeException;
import com.castlabs.dash.dashfragmenter.formats.csf.DashBuilder;
import com.castlabs.dash.dashfragmenter.formats.csf.SegmentBaseSingleSidxManifestWriterImpl;
import com.castlabs.dash.dashfragmenter.formats.multiplefilessegementtemplate.ExplodedSegmentListManifestWriterImpl;
import com.castlabs.dash.dashfragmenter.formats.multiplefilessegementtemplate.SingleSidxExplode;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.CompositionTimeToSample;
import com.coremedia.iso.boxes.Container;
import com.coremedia.iso.boxes.SampleDescriptionBox;
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry;
import com.googlecode.mp4parser.FileDataSourceImpl;
import com.googlecode.mp4parser.authoring.*;
import com.googlecode.mp4parser.authoring.builder.*;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.*;
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox;
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.AudioSpecificConfig;
import com.googlecode.mp4parser.boxes.mp4.samplegrouping.CencSampleEncryptionInformationGroupEntry;
import com.googlecode.mp4parser.util.Path;
import mpegDashSchemaMpd2011.MPDDocument;
import org.apache.xmlbeans.XmlOptions;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.logging.Logger;


/**
 *
 */
public class DashFileSetSequence {
    Logger l;
    static Set<String> supportedTypes = new HashSet<String>(Arrays.asList("ac-3", "ec-3", "dtsl", "dtsh", "dtse", "avc1", "mp4a", "h264"));

    protected SecretKey key;

    protected UUID keyid;

    protected List<X509Certificate> certificates;

    protected List<File> inputFiles;

    protected File outputDirectory = new File("");

    protected boolean explode = false;

    protected int sparse = 0;

    protected String encryptionAlgo = "cenc";

    public void setEncryptionAlgo(String encryptionAlgo) {
        this.encryptionAlgo = encryptionAlgo;
    }

    public void setKey(SecretKey key) {
        this.key = key;
    }

    public void setKeyid(UUID keyid) {
        this.keyid = keyid;
    }

    public void setCertificates(List<X509Certificate> certificates) {
        this.certificates = certificates;
    }

    public void setInputFiles(List<File> inputFiles) {
        this.inputFiles = inputFiles;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setExplode(boolean explode) {
        this.explode = explode;
    }

    public void setSparse(int sparse) {
        this.sparse = sparse;
    }

    public void setLogger(Logger l) {
        this.l = l;
    }

    public int run() throws IOException, ExitCodeException {

        if (!(outputDirectory.getAbsoluteFile().exists() ^ outputDirectory.getAbsoluteFile().mkdirs())) {
            l.severe("Output directory does not exist and cannot be created.");
        }

        long start = System.currentTimeMillis();

        Map<Track, String> track2File = createTracks();

        track2File = alignEditsToZero(track2File);
        track2File = fixAppleOddity(track2File);
        track2File = encryptTracks(track2File);


        // sort by language and codec
        Map<String, List<Track>> trackFamilies = findTrackFamilies(track2File.keySet());

        // Track sizes are expensive to calculate -> save them for later
        Map<Track, Long> trackSizes = calculateTrackSizes(trackFamilies);

        // sort within the track families by size to get stable output
        sortTrackFamilies(trackFamilies, trackSizes);

        // calculate the fragment start samples once & save them for later
        Map<Track, long[]> trackStartSamples = findFragmentStartSamples(trackFamilies);

        // calculate bitrates
        Map<Track, Long> trackBitrate = calculateBitrate(trackFamilies, trackSizes);

        // generate filenames for later reference
        Map<Track, String> trackFilename = generateFilenames(track2File);

        // export the dashed single track MP4s
        Map<Track, Container> dashedFiles = createSingleTrackDashedMp4s(trackStartSamples, trackFilename);

        if (!explode) {
            writeFilesSingleSidx(trackFilename, dashedFiles);
            writeManifestSingleSidx(trackFamilies, trackBitrate, trackFilename, dashedFiles);
        } else {
            String mediaPattern = "$RepresentationID$/media-$Time$.mp4";
            String initPattern = "$RepresentationID$/init.mp4";

            Map<Track, List<File>> trackToSegments =
                    writeFilesExploded(trackFilename, dashedFiles, trackBitrate, outputDirectory, initPattern, mediaPattern);

            writeManifestExploded(trackFamilies, trackBitrate, trackFilename, dashedFiles, trackToSegments, outputDirectory, initPattern, mediaPattern);
        }


        l.info("Finished write in " + (System.currentTimeMillis() - start) + "ms");
        return 0;
    }

    public Map<Track, List<File>> writeFilesExploded(
            Map<Track, String> trackFilename,
            Map<Track, Container> dashedFiles,
            Map<Track, Long> trackBitrate,
            File outputDirectory,
            String initPattern,
            String mediaPattern) throws IOException {
        Map<Track, List<File>> trackToSegments = new HashMap<Track, List<File>>();
        for (Track t : trackFilename.keySet()) {
            String filename = trackFilename.get(t);
            l.info("Writing... " + filename + "/...");
            File targetDir = new File(outputDirectory, filename);
            if (!(targetDir.getAbsoluteFile().exists() ^ targetDir.getAbsoluteFile().mkdirs())) {
                l.severe("target directory " + targetDir + " does not exist and cannot be created.");
            }
            SingleSidxExplode singleSidxExplode = new SingleSidxExplode();
            List<File> segments = new ArrayList<File>();

            singleSidxExplode.doIt(
                    trackFilename.get(t),
                    dashedFiles.get(t),
                    trackBitrate.get(t), segments, outputDirectory, initPattern, mediaPattern);

            l.info("Done.");
            trackToSegments.put(t, segments);
        }
        return trackToSegments;
    }

    public void writeFilesSingleSidx(Map<Track, String> trackFilename, Map<Track, Container> dashedFiles) throws IOException {
        for (Map.Entry<Track, Container> trackContainerEntry : dashedFiles.entrySet()) {
            l.info("Writing... ");
            WritableByteChannel wbc = new FileOutputStream(
                    new File(outputDirectory, trackFilename.get(trackContainerEntry.getKey()))).getChannel();
            try {
                List<Box> boxes = trackContainerEntry.getValue().getBoxes();
                for (int i = 0; i < boxes.size(); i++) {
                    l.fine("Writing... " + boxes.get(i).getType() + " [" + i + " of " + boxes.size() + "]");
                    boxes.get(i).getBox(wbc);
                }

            } finally {
                wbc.close();
            }
            l.info("Done.");
        }
    }


    public Mp4Builder getFileBuilder(FragmentIntersectionFinder fragmentIntersectionFinder, Movie m) {
        DashBuilder dashBuilder = new DashBuilder();
        dashBuilder.setIntersectionFinder(fragmentIntersectionFinder);
        return dashBuilder;
    }


    public Map<Track, Container> createSingleTrackDashedMp4s(
            Map<Track, long[]> fragmentStartSamples,
            Map<Track, String> filenames) throws IOException {

        HashMap<Track, Container> containers = new HashMap<Track, Container>();
        for (final Map.Entry<Track, long[]> trackEntry : fragmentStartSamples.entrySet()) {
            String filename = filenames.get(trackEntry.getKey());
            Movie movie = new Movie();
            movie.addTrack(trackEntry.getKey());

            l.info("Creating model for " + filename + "... ");
            Mp4Builder mp4Builder = getFileBuilder(
                    new StaticFragmentIntersectionFinderImpl(fragmentStartSamples),
                    movie);
            Container isoFile = mp4Builder.build(movie);
            containers.put(trackEntry.getKey(), isoFile);

        }
        return containers;
    }

    public void sortTrackFamilies(Map<String, List<Track>> trackFamilies, final Map<Track, Long> sizes) {
        for (List<Track> tracks : trackFamilies.values()) {
            Collections.sort(tracks, new Comparator<Track>() {
                public int compare(Track o1, Track o2) {
                    return (int) (sizes.get(o1) - sizes.get(o2));
                }
            });
        }
    }

    /**
     * Calculates approximate track size suitable for sorting & calculating bitrate but not suitable
     * for precise calculations.
     *
     * @param trackFamilies all tracks grouped by their type.
     * @return map from track to track's size
     */
    public Map<Track, Long> calculateTrackSizes(Map<String, List<Track>> trackFamilies) {
        HashMap<Track, Long> sizes = new HashMap<Track, Long>();
        for (List<Track> tracks : trackFamilies.values()) {
            for (Track track : tracks) {
                long size = 0;
                List<Sample> samples = track.getSamples();
                for (int i = 0; i < Math.min(samples.size(), 10000); i++) {
                    size += samples.get(i).getSize();
                }
                size = (size / Math.min(track.getSamples().size(), 10000)) * track.getSamples().size();
                sizes.put(track, size);
            }
        }
        return sizes;
    }

    /**
     * Calculates bitrate from sizes.
     *
     * @param trackFamilies all tracks grouped by their type.
     * @param trackSize     size per track
     * @return bitrate per track
     */
    public Map<Track, Long> calculateBitrate(Map<String, List<Track>> trackFamilies, Map<Track, Long> trackSize) {
        HashMap<Track, Long> bitrates = new HashMap<Track, Long>();
        for (List<Track> tracks : trackFamilies.values()) {
            for (Track track : tracks) {

                double duration = (double) track.getDuration() / track.getTrackMetaData().getTimescale();
                long size = trackSize.get(track);

                bitrates.put(track, (long) ((size * 8 / duration / 1000)) * 1000);
            }

        }
        return bitrates;
    }

    /**
     * Generates filenames from type, language and bitrate.
     *
     * @return a descriptive filename <code>type[-lang]-bitrate.mp4</code>
     */
    public Map<Track, String> generateFilenames(Map<Track, String> trackOriginalFilename) {
        HashMap<Track, String> filenames = new HashMap<Track, String>();
        for (Track track : trackOriginalFilename.keySet()) {
            String originalFilename = trackOriginalFilename.get(track);
            originalFilename = originalFilename.replace(".mp4", "");
            originalFilename = originalFilename.replace(".mov", "");
            originalFilename = originalFilename.replace(".aac", "");
            originalFilename = originalFilename.replace(".ec3", "");
            originalFilename = originalFilename.replace(".ac3", "");
            originalFilename = originalFilename.replace(".dtshd", "");
            for (Track track1 : filenames.keySet()) {
                if (track1 != track &&
                        trackOriginalFilename.get(track1).equals(trackOriginalFilename.get(track))) {
                    // ouch multiple tracks point to same file
                    originalFilename += "_" + track.getTrackMetaData().getTrackId();
                }
            }
            if (!explode) {
                filenames.put(track, String.format("%s.mp4", originalFilename));
            } else {
                filenames.put(track, originalFilename);
            }

        }
        return filenames;
    }

    public Map<Track, long[]> findFragmentStartSamples(Map<String, List<Track>> trackFamilies) {
        Map<Track, long[]> fragmentStartSamples = new HashMap<Track, long[]>();

        for (String key : trackFamilies.keySet()) {
            List<Track> tracks = trackFamilies.get(key);
            Movie movie = new Movie();
            movie.setTracks(tracks);
            for (Track track : tracks) {
                if (track.getHandler().startsWith("vide")) {
                    FragmentIntersectionFinder videoIntersectionFinder = new SyncSampleIntersectFinderImpl(movie, null, 2);
                    fragmentStartSamples.put(track, videoIntersectionFinder.sampleNumbers(track));
                    //fragmentStartSamples.put(track, checkMaxFragmentDuration(track, videoIntersectionFinder.sampleNumbers(track)));
                } else if (track.getHandler().startsWith("soun")) {
                    FragmentIntersectionFinder soundIntersectionFinder = new TwoSecondIntersectionFinder(movie, 5);
                    fragmentStartSamples.put(track, soundIntersectionFinder.sampleNumbers(track));
                } else {
                    throw new RuntimeException("An engineer needs to tell me if " + key + " is audio or video!");
                }
            }
        }
        return fragmentStartSamples;
    }

    /**
     * Creates a Map with Track as key and originating filename as value.
     *
     * @return Track too originating file map
     * @throws IOException
     */
    public Map<Track, String> createTracks() throws IOException, ExitCodeException {
        Map<Track, String> track2File = new HashMap<Track, String>();
        for (File inputFile : inputFiles) {
            if (inputFile.getName().endsWith(".mp4") ||
                    inputFile.getName().endsWith(".mov") ||
                    inputFile.getName().endsWith(".m4v")) {
                Movie movie = MovieCreator.build(new FileDataSourceImpl(inputFile));
                for (Track track : movie.getTracks()) {
                    String codec = track.getSampleDescriptionBox().getSampleEntry().getType();
                    if (!supportedTypes.contains(codec)) {
                        l.warning("Excluding " + inputFile + " track " + track.getTrackMetaData().getTrackId() + " as its codec " + codec + " is not yet supported");
                        break;
                    }
                    track2File.put(track, inputFile.getName());
                }
            } else if (inputFile.getName().endsWith(".aac")) {
                Track track = new AACTrackImpl(new FileDataSourceImpl(inputFile));
                track2File.put(track, inputFile.getName());
                l.fine("Created AAC Track from " + inputFile.getName());
            } else if (inputFile.getName().endsWith(".h264")) {
                Track track = new H264TrackImpl(new FileDataSourceImpl(inputFile));
                track2File.put(track, inputFile.getName());
                l.fine("Created H264 Track from " + inputFile.getName());
            } else if (inputFile.getName().endsWith(".ac3")) {
                Track track = new AC3TrackImpl(new FileDataSourceImpl(inputFile));
                track2File.put(track, inputFile.getName());
                l.fine("Created AC3 Track from " + inputFile.getName());
            } else if (inputFile.getName().endsWith(".ec3")) {
                Track track = new EC3TrackImpl(new FileDataSourceImpl(inputFile));
                track2File.put(track, inputFile.getName());
                l.fine("Created EC3 Track from " + inputFile.getName());
            } else if (inputFile.getName().endsWith(".dtshd")) {
                Track track = new DTSTrackImpl(new FileDataSourceImpl(inputFile));
                track2File.put(track, inputFile.getName());
                l.fine("Created DTS HD Track from " + inputFile.getName());
            } else {
                l.severe("Cannot identify type of " + inputFile + ". Extensions mp4, mov, m4v, aac, ac3, ec3 or dtshd are known.");
                throw new ExitCodeException("Cannot identify type of " + inputFile + ". Extensions mp4, mov, m4v, aac, ac3, ec3 or dtshd are known.", 1);
            }
        }


        return track2File;

    }

    public Map<Track, String> encryptTracks(Map<Track, String> track2File) {
        if (this.key != null && this.keyid != null) {
            Map<Track, String> encTracks = new HashMap<Track, String>();
            for (Map.Entry<Track, String> trackStringEntry : track2File.entrySet()) {
                String hdlr = trackStringEntry.getKey().getHandler();
                if ("vide".equals(hdlr) || "soun".equals(hdlr)) {
                    if (sparse == 0) {
                        CencEncryptingTrackImpl cencTrack = new CencEncryptingTrackImpl(
                                trackStringEntry.getKey(), keyid,
                                Collections.singletonMap(keyid, key),
                                null, encryptionAlgo);
                        encTracks.put(cencTrack, trackStringEntry.getValue());
                    } else if (sparse == 1) {
                        CencSampleEncryptionInformationGroupEntry e = new CencSampleEncryptionInformationGroupEntry();
                        e.setEncrypted(false);
                        Track t = trackStringEntry.getKey();
                        long[] excludes;
                        if (t.getSyncSamples() != null && t.getSyncSamples().length > 0) {
                            excludes = new long[t.getSamples().size() - t.getSyncSamples().length];
                            int excludesIndex = 0;
                            for (long i = 1; i <= t.getSamples().size(); i++) {
                                if (Arrays.binarySearch(t.getSyncSamples(), i) < 0) {
                                    excludes[excludesIndex++] = i - 1;
                                }

                            }
                        } else {
                            excludes = new long[t.getSamples().size() / 3];
                            for (int i = 0; i < excludes.length; i++) {
                                excludes[i] = i * 3;
                            }
                        }

                        CencEncryptingTrackImpl cencTrack = new CencEncryptingTrackImpl(
                                t, keyid,
                                Collections.singletonMap(keyid, key),
                                Collections.singletonMap(e, excludes),
                                "cenc");
                        encTracks.put(cencTrack, trackStringEntry.getValue());

                    } else if (sparse == 2) {
                        CencSampleEncryptionInformationGroupEntry e = new CencSampleEncryptionInformationGroupEntry();
                        e.setEncrypted(true);
                        e.setKid(keyid);
                        e.setIvSize(8);
                        Track t = trackStringEntry.getKey();

                        long[] includes;
                        if (t.getSyncSamples() != null && t.getSyncSamples().length > 0) {
                            includes = Arrays.copyOf(t.getSyncSamples(), t.getSyncSamples().length);
                            for (int i = 0; i < includes.length; i++) {
                                includes[i] -= 1;
                            }
                        } else {
                            SecureRandom r = new SecureRandom();
                            int encSamples = t.getSamples().size() / 10;
                            includes = new long[encSamples];
                            while (--encSamples >= 0) {
                                includes[encSamples] = r.nextInt(t.getSamples().size());
                            }
                            Arrays.sort(includes);
                        }
                        encTracks.put(new CencEncryptingTrackImpl(
                                t, null,
                                Collections.singletonMap(keyid, key),
                                Collections.singletonMap(e, includes),
                                "cenc"), trackStringEntry.getValue());
                    }
                } else {
                    encTracks.put(trackStringEntry.getKey(), trackStringEntry.getValue());
                }
            }
            track2File = encTracks;
        }
        return track2File;
    }

    public Map<Track, String> fixAppleOddity(Map<Track, String> track2File) {
        Map<Track, String> nuTracks = new HashMap<Track, String>();

        for (Map.Entry<Track, String> entry : track2File.entrySet()) {
            Track track = entry.getKey();
            if (Path.getPath(track.getSampleDescriptionBox(), "...a/wave/esds") != null) {
                final SampleDescriptionBox stsd = track.getSampleDescriptionBox();
                AudioSampleEntry ase = (AudioSampleEntry) stsd.getSampleEntry();
                List<Box> aseBoxes = new ArrayList<Box>();
                aseBoxes.add(Path.getPath(stsd, "...a/wave/esds"));
                for (Box box : ase.getBoxes()) {
                    if (!box.getType().equals("wave")) {
                        aseBoxes.add(box);
                    }
                }
                ase.setBoxes(Collections.<Box>emptyList());
                for (Box aseBox : aseBoxes) {
                    ase.addBox(aseBox);
                }
                nuTracks.put(new StsdCorrectingTrack(track, stsd), entry.getValue());
            } else {
                nuTracks.put(entry.getKey(), entry.getValue());
            }
        }
        return nuTracks;
    }

    public Map<Track, String> alignEditsToZero(Map<Track, String> tracks) {
        Map<Track, String> result = new HashMap<Track, String>();
        double earliestMoviePresentationTime = 0;
        Map<Track, Double> startTimes = new HashMap<Track, Double>();
        Map<Track, Double> ctsOffset = new HashMap<Track, Double>();

        for (Track track : tracks.keySet()) {
            boolean acceptEdit = true;
            boolean acceptDwell = true;
            List<Edit> edits = track.getEdits();
            double earliestTrackPresentationTime = 0;
            for (Edit edit : edits) {
                if (edit.getMediaTime() == -1 && !acceptDwell) {
                    throw new RuntimeException("Cannot accept edit list for processing (1)");
                }
                if (edit.getMediaTime() >= 0 && !acceptEdit) {
                    throw new RuntimeException("Cannot accept edit list for processing (2)");
                }
                if (edit.getMediaTime() == -1) {
                    earliestTrackPresentationTime += edit.getSegmentDuration();
                } else /* if edit.getMediaTime() >= 0 */ {
                    earliestTrackPresentationTime -= (double) edit.getMediaTime() / edit.getTimeScale();
                    acceptEdit = false;
                    acceptDwell = false;
                }
            }

            if (track.getCompositionTimeEntries() != null && track.getCompositionTimeEntries().size() > 0) {
                long currentTime = 0;
                int[] ptss = Arrays.copyOfRange(CompositionTimeToSample.blowupCompositionTimes(track.getCompositionTimeEntries()), 0, 50);
                for (int j = 0; j < ptss.length; j++) {
                    ptss[j] += currentTime;
                    currentTime += track.getSampleDurations()[j];
                }
                Arrays.sort(ptss);
                earliestTrackPresentationTime += (double) ptss[0] / track.getTrackMetaData().getTimescale();
                ctsOffset.put(track, (double) ptss[0] / track.getTrackMetaData().getTimescale());
            } else {
                ctsOffset.put(track, 0.0);
            }
            startTimes.put(track, earliestTrackPresentationTime);
            earliestMoviePresentationTime = Math.min(earliestMoviePresentationTime, earliestTrackPresentationTime);
            System.err.println(track.getName() + "'s starttime after edits: " + earliestTrackPresentationTime);
        }
        for (Track track : tracks.keySet()) {
            double adjustedStartTime = startTimes.get(track) - earliestMoviePresentationTime - ctsOffset.get(track);

            final List<Edit> edits = new ArrayList<Edit>();
            if (adjustedStartTime < 0) {
                edits.add(new Edit((long) (-adjustedStartTime * track.getTrackMetaData().getTimescale()), track.getTrackMetaData().getTimescale(), 1.0, (double) track.getDuration() / track.getTrackMetaData().getTimescale()));
            } else if (adjustedStartTime > 0) {
                edits.add(new Edit(-1, track.getTrackMetaData().getTimescale(), 1.0, adjustedStartTime));
                edits.add(new Edit(0, track.getTrackMetaData().getTimescale(), 1.0, (double) track.getDuration() / track.getTrackMetaData().getTimescale()));

            }

            result.put(new WrappingTrack(track) {
                @Override
                public List<Edit> getEdits() {
                    return edits;
                }
            }, tracks.get(track));
        }
        return result;
    }

    public Map<String, List<Track>> findTrackFamilies(Set<Track> allTracks) throws IOException {
        HashMap<String, List<Track>> trackFamilies = new HashMap<String, List<Track>>();
        for (Track track : allTracks) {
            String family = track.getSampleDescriptionBox().getSampleEntry().getType() + "-" + track.getTrackMetaData().getLanguage();
            if ("mp4a".equals(track.getSampleDescriptionBox().getSampleEntry().getType())) {
                // we need to look at actual channel configuration
                ESDescriptorBox esds = track.getSampleDescriptionBox().getSampleEntry().getBoxes(ESDescriptorBox.class).get(0);
                AudioSpecificConfig audioSpecificConfig = esds.getEsDescriptor().getDecoderConfigDescriptor().getAudioSpecificInfo();
                family += "-" + audioSpecificConfig.getChannelConfiguration();
            }

            List<Track> tracks = trackFamilies.get(family);
            if (tracks == null) {
                tracks = new LinkedList<Track>();
                trackFamilies.put(family, tracks);
            }
            tracks.add(track);
        }

        return trackFamilies;
    }

    public void writeManifestExploded(Map<String, List<Track>> trackFamilies,
                                      Map<Track, Long> trackBitrate,
                                      Map<Track, String> trackFilename,
                                      Map<Track, Container> dashedFiles,
                                      Map<Track, List<File>> trackToSegments,
                                      File outputDirectory, String initPattern, String mediaPattern) throws IOException {
        Map<Track, UUID> trackKeyIds = new HashMap<Track, UUID>();
        for (List<Track> tracks : trackFamilies.values()) {
            for (Track track : tracks) {
                trackKeyIds.put(track, this.keyid);
            }
        }
        MPDDocument mpdDocument =
                new ExplodedSegmentListManifestWriterImpl(
                        trackFamilies, dashedFiles, trackBitrate, trackFilename,
                        trackKeyIds, trackToSegments, initPattern, mediaPattern).getManifest();

        XmlOptions xmlOptions = new XmlOptions();
        //xmlOptions.setUseDefaultNamespace();
        HashMap<String, String> ns = new HashMap<String, String>();
        //ns.put("urn:mpeg:DASH:schema:MPD:2011", "");
        ns.put("urn:mpeg:cenc:2013", "cenc");
        xmlOptions.setSaveSuggestedPrefixes(ns);
        xmlOptions.setSaveAggressiveNamespaces();
        xmlOptions.setUseDefaultNamespace();
        xmlOptions.setSavePrettyPrint();
        File manifest1 = new File(outputDirectory, "Manifest.mpd");
        l.info("Writing " + manifest1 + "... ");
        mpdDocument.save(manifest1, xmlOptions);
        l.info("Done.");

    }

    public void writeManifestSingleSidx(Map<String, List<Track>> trackFamilies, Map<Track, Long> trackBitrate, Map<Track, String> trackFilename, Map<Track, Container> dashedFiles) throws IOException {

        Map<Track, UUID> trackKeyIds = new HashMap<Track, UUID>();
        for (List<Track> tracks : trackFamilies.values()) {
            for (Track track : tracks) {
                trackKeyIds.put(track, this.keyid);
            }
        }
        SegmentBaseSingleSidxManifestWriterImpl dashManifestWriter = new SegmentBaseSingleSidxManifestWriterImpl(
                trackFamilies, dashedFiles,
                trackBitrate, trackFilename,
                trackKeyIds);
        MPDDocument mpdDocument = dashManifestWriter.getManifest();

        XmlOptions xmlOptions = new XmlOptions();
        //xmlOptions.setUseDefaultNamespace();
        HashMap<String, String> ns = new HashMap<String, String>();
        //ns.put("urn:mpeg:DASH:schema:MPD:2011", "");
        ns.put("urn:mpeg:cenc:2013", "cenc");
        xmlOptions.setSaveSuggestedPrefixes(ns);
        xmlOptions.setSaveAggressiveNamespaces();
        xmlOptions.setUseDefaultNamespace();
        xmlOptions.setSavePrettyPrint();

        mpdDocument.save(new File(this.outputDirectory, "Manifest.mpd"), xmlOptions);
    }

    private class StsdCorrectingTrack extends AbstractTrack {
        Track track;
        SampleDescriptionBox stsd;

        public StsdCorrectingTrack(Track track, SampleDescriptionBox stsd) {
            super(track.getName());
            this.track = track;
            this.stsd = stsd;
        }


        public void close() throws IOException {
            track.close();
        }


        public SampleDescriptionBox getSampleDescriptionBox() {
            return stsd;
        }


        public long[] getSampleDurations() {
            return track.getSampleDurations();
        }


        public TrackMetaData getTrackMetaData() {
            return track.getTrackMetaData();
        }


        public String getHandler() {
            return track.getHandler();
        }


        public List<Sample> getSamples() {
            return track.getSamples();
        }
    }
}
