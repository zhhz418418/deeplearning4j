package org.deeplearning4j.ui.stats;

import org.apache.commons.io.IOUtils;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.ui.stats.api.*;
import org.deeplearning4j.ui.stats.temp.HistogramBin;
import org.nd4j.linalg.api.buffer.util.DataTypeUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;

import java.io.InputStream;
import java.lang.management.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Alex on 28/09/2016.
 */
public class StatsListener implements IterationListener {

    private enum StatType {Mean, Stdev, MeanMagnitude}

    private final StatsListenerReceiver receiver;
    private int iterCount = 0;

    private long initTime;
    private long lastReportTime = -1;
    private int lastReportIteration = -1;
    private int examplesSinceLastReport = 0;
    private int minibatchesSinceLastReport = 0;

    private long totalExamples = 0;
    private long totalMinibatches = 0;

    private List<GarbageCollectorMXBean> gcBeans;
    private Map<String,Pair<Long,Long>> gcStatsAtLastReport;

    public StatsListener(StatsListenerReceiver receiver) {
        this.receiver = receiver;
    }

    @Override
    public boolean invoked() {
        return false;
    }

    @Override
    public void invoke() {

    }

    @Override
    public void iterationDone(Model model, int iteration) {
        StatsListenerConfiguration config = receiver.getCurrentConfiguration();

        long currentTime = getTime();
        if (iterCount == 0) {
            initTime = currentTime;
            doInit(model);
        }

        if (config.collectPerformanceStats()) {
            updateExamplesMinibatchesCounts(model);
        }

        if (config.reportingFrequency() > 1 && (iterCount == 0 || iterCount % config.reportingFrequency() != 0)) {
            iterCount++;
            return;
        }

        StatsReport report = receiver.newStatsReport();
        report.reportTime(currentTime);
        report.reportIterationCount(iterCount);

        long deltaReportTime = currentTime - lastReportTime;

        //--- Performance and System Stats ---
        if (config.collectPerformanceStats()) {
            //Stats to collect: total runtime, total examples, total minibatches, iterations/second, examples/second
            double examplesPerSecond;
            double minibatchesPerSecond;
            if (iterCount == 0) {
                //Not possible to work out perf/second: first iteration...
                examplesPerSecond = 0.0;
                minibatchesPerSecond = 0.0;
            } else {
                long deltaTimeMS = currentTime - lastReportTime;
                examplesPerSecond = 1000.0 * examplesSinceLastReport / deltaTimeMS;
                minibatchesPerSecond = 1000.0 * minibatchesSinceLastReport / deltaTimeMS;
            }
            long totalRuntimeMS = currentTime - initTime;
            report.reportPerformance(totalRuntimeMS, totalExamples, totalMinibatches, examplesPerSecond, minibatchesPerSecond);

            examplesSinceLastReport = 0;
            minibatchesSinceLastReport = 0;
        }

        if (config.collectMemoryStats()) {

            Runtime runtime = Runtime.getRuntime();
            long jvmTotal = runtime.totalMemory();
            long jvmMax = runtime.maxMemory();

            //Off-heap memory
            long offheapTotal = Pointer.totalBytes();
            long offheapMax = Pointer.maxBytes();

            //GPU
            long[] gpuCurrentBytes = null;
            long[] gpuMaxBytes = null;
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            int nDevices = nativeOps.getAvailableDevices();
            if(nDevices > 0){
                gpuCurrentBytes = new long[nDevices];
                gpuMaxBytes = new long[nDevices];
            }

            report.reportMemoryUse(jvmTotal, jvmMax, offheapTotal, offheapMax, gpuCurrentBytes, gpuMaxBytes);
        }

        if(config.collectGarbageCollectionStats()){
            if(lastReportIteration == -1 || gcBeans == null){
                //Haven't reported GC stats before...
                gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
                gcStatsAtLastReport = new HashMap<>();
                for( GarbageCollectorMXBean bean : gcBeans ){
                    long count = bean.getCollectionCount();
                    long timeMs = bean.getCollectionTime();
                    gcStatsAtLastReport.put(bean.getName(), new Pair<>(count,timeMs));
                }
            } else {
                for( GarbageCollectorMXBean bean : gcBeans ){
                    long count = bean.getCollectionCount();
                    long timeMs = bean.getCollectionTime();
                    Pair<Long,Long> lastStats = gcStatsAtLastReport.get(bean.getName());
                    long deltaGCCount = count - lastStats.getFirst();
                    long deltaGCTime = timeMs - lastStats.getSecond();

                    lastStats.setFirst(count);
                    lastStats.setSecond(timeMs);
                    report.reportGarbageCollection(bean.getName(), (int)deltaGCCount, (int)deltaGCTime);
                }
            }
        }

        //--- General ---
        report.reportScore(model.score());  //Always report score

        if (config.collectLearningRates()) {
            Layer[] layers = null;
            if(model instanceof MultiLayerNetwork){
                layers = ((MultiLayerNetwork) model).getLayers();
            } else if(model instanceof ComputationGraph){
                ((ComputationGraph) model).getLayers();
            }

            if(layers != null){
                Map<String,Double> lrs = new HashMap<>();
                for(Layer l : layers){
                    NeuralNetConfiguration conf = l.conf();
                    lrs.putAll(conf.getLearningRateByParam());
                }

                report.reportLearningRates(lrs);
            }
        }


        //--- Histograms ---

        if (config.collectHistograms(StatsType.Parameters)) {
            Map<String, Histogram> paramHistograms = getHistograms(model.paramTable(), config.numHistogramBins(StatsType.Parameters));
            report.reportHistograms(StatsType.Parameters, paramHistograms);
        }

        if (config.collectHistograms(StatsType.Updates)) {
            Map<String, Histogram> updateHistograms = getHistograms(model.gradient().gradientForVariable(), config.numHistogramBins(StatsType.Updates));
            report.reportHistograms(StatsType.Updates, updateHistograms);
        }

        if (config.collectHistograms(StatsType.Activations)) {
            Map<String, INDArray> activations = getActivationArraysMap(model);
            Map<String, Histogram> activationHistograms = getHistograms(activations, config.numHistogramBins(StatsType.Activations));
            report.reportHistograms(StatsType.Activations, activationHistograms);
        }


        //--- Summary Stats: Mean, Variance, Mean Magnitudes ---

        if (config.collectMean(StatsType.Parameters)) {
            Map<String, Double> meanParams = calculateSummaryStats(model.paramTable(), StatType.Mean);
            report.reportMean(StatsType.Parameters, meanParams);
        }

        if (config.collectMean(StatsType.Updates)) {
            Map<String, Double> meanUpdates = calculateSummaryStats(model.gradient().gradientForVariable(), StatType.Mean);
            report.reportMean(StatsType.Updates, meanUpdates);
        }

        if (config.collectMean(StatsType.Activations)) {
            Map<String, INDArray> activations = getActivationArraysMap(model);
            Map<String, Double> meanActivations = calculateSummaryStats(activations, StatType.Mean);
            report.reportMean(StatsType.Activations, meanActivations);
        }


        if (config.collectStdev(StatsType.Parameters)) {
            Map<String, Double> stdevParams = calculateSummaryStats(model.paramTable(), StatType.Stdev);
            report.reportStdev(StatsType.Parameters, stdevParams);
        }

        if (config.collectStdev(StatsType.Updates)) {
            Map<String, Double> stdevUpdates = calculateSummaryStats(model.gradient().gradientForVariable(), StatType.Stdev);
            report.reportStdev(StatsType.Updates, stdevUpdates);
        }

        if (config.collectStdev(StatsType.Activations)) {
            Map<String, INDArray> activations = getActivationArraysMap(model);
            Map<String, Double> stdevActivations = calculateSummaryStats(activations, StatType.Stdev);
            report.reportStdev(StatsType.Activations, stdevActivations);
        }


        if (config.collectMeanMagnitudes(StatsType.Parameters)) {
            Map<String, Double> meanMagParams = calculateSummaryStats(model.paramTable(), StatType.MeanMagnitude);
            report.reportMeanMagnitudes(StatsType.Parameters, meanMagParams);
        }

        if (config.collectMeanMagnitudes(StatsType.Updates)) {
            Map<String, Double> meanMagUpdates = calculateSummaryStats(model.gradient().gradientForVariable(), StatType.MeanMagnitude);
            report.reportMeanMagnitudes(StatsType.Updates, meanMagUpdates);
        }

        if (config.collectMeanMagnitudes(StatsType.Activations)) {
            Map<String, INDArray> activations = getActivationArraysMap(model);
            Map<String, Double> meanMagActivations = calculateSummaryStats(activations, StatType.MeanMagnitude);
            report.reportMeanMagnitudes(StatsType.Activations, meanMagActivations);
        }


        long endTime = getTime();
        report.reportStatsCollectionDurationMS((int)(endTime-currentTime));    //Amount of time required to alculate all histograms, means etc.
        lastReportTime = currentTime;
        lastReportIteration = iterCount;
        receiver.postStatsReport(report);
        iterCount++;
    }

    private long getTime() {
        //Abstraction to allow NTP to be plugged in later...
        return System.currentTimeMillis();
    }

    private void doInit(Model model){

        StatsInitializationConfiguration initConfiguration = receiver.getInitializationConfiguration();
        StatsInitializationReport initReport = receiver.newInitializationReport();

        if(initConfiguration.collectSoftwareInfo()){
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

            String arch = osBean.getArch();
            String osName = osBean.getName();
            String jvmName = runtime.getVmName();
            String jvmVersion = runtime.getVmVersion();
            String jvmSpecVersion = runtime.getSpecVersion();

            String nd4jBackendClass = Nd4j.getNDArrayFactory().getClass().getName();
            String nd4jDataTypeName = DataTypeUtil.getDtypeFromContext().name();

            String hostname = System.getenv("COMPUTERNAME");
            if(hostname == null || hostname.isEmpty()){
                try{
                    Process proc = Runtime.getRuntime().exec("hostname");
                    try (InputStream stream = proc.getInputStream()) {
                        hostname = IOUtils.toString(stream);
                    }
                }catch(Exception e){ }
            }


            initReport.reportSoftwareInfo(arch, osName, jvmName, jvmVersion, jvmSpecVersion,
                    nd4jBackendClass, nd4jDataTypeName, hostname);
        }

        if(initConfiguration.collectHardwareInfo()){
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            int nDevices = nativeOps.getAvailableDevices();

            long[] deviceTotalMem = null;
            if(nDevices > 0){
                deviceTotalMem = new long[nDevices];
                for( int i=0; i<nDevices; i++ ){
                    deviceTotalMem[i] = nativeOps.getDeviceTotalMemory(new IntPointer(i));
                }
            }
            long jvmMaxMemory = Runtime.getRuntime().maxMemory();
            long offheapMaxMemory = Pointer.maxBytes();

            String[] deviceDescription = null;  //TODO

            initReport.reportHardwareInfo(availableProcessors, nDevices, jvmMaxMemory, offheapMaxMemory, deviceTotalMem, deviceDescription);
        }

        if(initConfiguration.collectModelInfo()){
            String jsonConf;
            int numLayers;
            int numParams;
            if(model instanceof MultiLayerNetwork){
                MultiLayerNetwork net = ((MultiLayerNetwork)model);
                jsonConf = net.getLayerWiseConfigurations().toJson();
                numLayers = net.getnLayers();
                numParams = net.numParams();
            } else if(model instanceof ComputationGraph){
                ComputationGraph cg = ((ComputationGraph)model);
                jsonConf = cg.getConfiguration().toJson();
                numLayers = cg.getNumLayers();
                numParams = cg.numParams();
            } else {
                throw new RuntimeException();
            }

            Map<String,INDArray> paramMap = model.paramTable();
            String[] paramNames = new String[paramMap.size()];
            int i=0;
            for(String s : paramMap.keySet()){      //Assuming sensible iteration order - LinkedHashMaps are used in MLN/CG for example
                paramNames[i++] = s;
            }

            initReport.reportModelInfo(model.getClass().getName(), jsonConf, paramNames, numLayers, numParams);
        }

        receiver.postInitializationReport(initReport);
    }

    private void updateExamplesMinibatchesCounts(Model model) {
        int examplesThisMinibatch = 0;
        if (model instanceof MultiLayerNetwork) {
            examplesThisMinibatch = ((MultiLayerNetwork) model).getInput().size(0);
        } else if (model instanceof ComputationGraph) {
            examplesThisMinibatch = ((ComputationGraph) model).getInput(0).size(0);
        } else if (model instanceof Layer) {
            examplesThisMinibatch = ((Layer) model).getInputMiniBatchSize();
        }
        examplesSinceLastReport += examplesThisMinibatch;
        totalExamples += examplesThisMinibatch;
        minibatchesSinceLastReport++;
        totalMinibatches++;
    }

    private static Map<String, Double> calculateSummaryStats(Map<String, INDArray> source, StatType statType) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, INDArray> entry : source.entrySet()) {
            String name = entry.getKey();
            double value;
            switch (statType) {
                case Mean:
                    value = entry.getValue().meanNumber().doubleValue();
                    break;
                case Stdev:
                    value = entry.getValue().stdNumber().doubleValue();
                    break;
                case MeanMagnitude:
                    value = entry.getValue().norm1Number().doubleValue() / entry.getValue().length();
                    break;
                default:
                    throw new RuntimeException();   //Should never happen
            }
            out.put(name, value);
        }
        return out;
    }

    private static Map<String, Histogram> getHistograms(Map<String, INDArray> map, int nBins) {
        //TODO This is temporary approach...
        Map<String, Histogram> out = new LinkedHashMap<>();

        for (Map.Entry<String, INDArray> entry : map.entrySet()) {
            HistogramBin histogram = new HistogramBin.Builder(entry.getValue().dup())
                    .setBinCount(nBins)
                    .setRounding(6)
                    .build();
            INDArray bins = histogram.getBins();
            int[] count = new int[nBins];
            int i = 0;
            for (Map.Entry<BigDecimal, AtomicInteger> e : histogram.getData().entrySet()) {
                count[i] = e.getValue().get();
            }

            double min = histogram.getMin();
            double max = histogram.getMax();

            Histogram h = new Histogram(min, max, nBins, count);

            out.put(entry.getKey(), h);
        }
        return out;
    }

    private static Map<String, INDArray> getActivationArraysMap(Model model) {
        Map<String, INDArray> map = new LinkedHashMap<>();
        if (model instanceof MultiLayerNetwork) {
            MultiLayerNetwork net = (MultiLayerNetwork) model;

            Layer[] layers = net.getLayers();
            //Activations for layer i are stored as input to layer i+1
            //TODO handle output activations...
            //Also: complication here - things like batch norm...
            for (int i = 1; i < layers.length; i++) {
                String name = String.valueOf(i - 1);
                map.put(name, layers[i].input());
            }

        } else {
            //Compgraph is more complex: output from one layer might go to multiple other layers/vertices, etc.
            throw new UnsupportedOperationException("Not yet implemented");
        }

        return map;
    }
}