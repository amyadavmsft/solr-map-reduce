package org.apache.solr.hadoop;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.math.IntMath;
import org.apache.commons.cli.*;
import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;

/**
 * MapReduce driver which merges multiple Solr indices into a single index.  This should be run on the output of
 * {@link SolrIndexDriver}.  The configured number of shards and reducers should match the configuration used when
 * running {@link SolrIndexDriver}.
 * @author Joe Linn
 * 12/30/2019
 */
public class SolrMergeDriver extends Configured implements Tool {
    private static final Logger log = LoggerFactory.getLogger(SolrMergeDriver.class);


    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new SolrMergeDriver(), args));
    }


    @Override
    public int run(String[] args) throws Exception {
        Options options = new Options();

        Option shardsOption = new Option("s", "shards", true, "Desired number of shards.");
        shardsOption.setRequired(true);
        options.addOption(shardsOption);

        Option reducersOption = new Option("r", "reducers", true, "Number of reducers used by the SolrIndexDriver");
        reducersOption.setRequired(true);
        options.addOption(reducersOption);

        Option inputOption = new Option("i", "input-dir", true, "Input directory.");
        inputOption.setRequired(true);
        options.addOption(inputOption);

        Option log4jOption = new Option("l4", "log4j", true, "log4j file location");
        options.addOption(log4jOption);

        Option overwriteOption = new Option("ow", "overwrite", false, "If true, any existing data in the output directory will be deleted.");
        options.addOption(overwriteOption);

        Option fanoutOption = new Option("f", "fanout", true, "Fanout");
        options.addOption(fanoutOption);

        CommandLineParser parser = new GnuParser();
        CommandLine commandLine = parser.parse(options, args);

        String input = commandLine.getOptionValue(inputOption.getOpt());

        int reducers = Integer.parseInt(commandLine.getOptionValue(reducersOption.getOpt()));
        int shards = Integer.parseInt(commandLine.getOptionValue(shardsOption.getOpt()));
        int fanout = Math.min(Integer.parseInt(commandLine.getOptionValue(fanoutOption.getOpt(), String.valueOf(Integer.MAX_VALUE))), IntMath.divide(reducers, shards, RoundingMode.CEILING));
        if (fanout < 2 || reducers % fanout != 0) {
            throw new IllegalArgumentException("Fanout must be >= 2 and reducers % fanout must == 0");
        }

        if (commandLine.hasOption(log4jOption.getOpt())) {
            Utils.configureLog4jProperties(commandLine.getOptionValue(log4jOption.getOpt()));
        }

        Path reducersPath = new Path(input, "reducers");
        if (!reducersPath.getFileSystem(getConf()).exists(reducersPath)) {
            throw new IllegalStateException("Directory " + reducersPath + " does not exist.");
        }

        Path outputPath = new Path(commandLine.getOptionValue(inputOption.getOpt()));  // final index output path
        Path mergeOutputPath = new Path(input, "mtree-merge-output");


        int mtreeMergeIteration = 1;
        Job job = null;
        while (reducers > shards) {
            job = Job.getInstance(getConf());
            job.setJobName("Solr offline index merger: " + input);
            job.setJarByClass(getClass());

            job.setMapperClass(TreeMergeMapper.class);
            job.setOutputFormatClass(TreeMergeOutputFormat.class);
            job.setNumReduceTasks(0);
            job.setOutputKeyClass(Text.class);
            job.setOutputValueClass(NullWritable.class);
            job.setInputFormatClass(NLineInputFormat.class);

            Path inputStepDir = new Path(outputPath, "mtree-merge-input-iteration" + mtreeMergeIteration);
            Path inputList = new Path(inputStepDir, "input-list.txt");
            if (log.isDebugEnabled()) {
                log.debug("MTree merge iteration {}/{}: Creating input list file for mappers at {}", mtreeMergeIteration, 1, inputList);    //TODO: use an actual number of total iterations rather than 1
            }
            int numDirs = createTreeMergeInputList(reducersPath, job, inputList);
            if (numDirs != reducers) {
                throw new IllegalStateException("Configured number of reducers (" + reducers + ") does not match number of input dirs: " + numDirs);
            }
            NLineInputFormat.addInputPath(job, inputList);
            NLineInputFormat.setNumLinesPerSplit(job, fanout);
            FileOutputFormat.setOutputPath(job, mergeOutputPath);
            log.info("MTree merge iteration {}/{}: Merging {} shards into {} shards using fanout {}", mtreeMergeIteration, 1, reducers, (reducers / fanout), fanout);
            if (!job.waitForCompletion(true)) {
                return -1;  // merge job failed
            }

            // merge job succeeded. Delete reducer output directory.
            if (!reducersPath.getFileSystem(getConf()).delete(reducersPath, true)) {
                log.error("Unable to delete " + reducersPath);
                return -1;
            }
            // rename merge output to reducer output path in preparation for next merge iteration.
            if (!rename(mergeOutputPath, reducersPath, mergeOutputPath.getFileSystem(getConf()))) {
                log.error("Unable to rename " + mergeOutputPath + " to " + reducersPath);
                return -1;
            }

            reducers = reducers / fanout;
            mtreeMergeIteration++;
        }

        if (!renameTreeMergeShardDirs(reducersPath, job, reducersPath.getFileSystem(job.getConfiguration()))) {
            return -1;
        }
        // normalize output shard dir prefix, i.e.
        // rename part-r-00000 to part-00000 (stems from zero tree merge iterations)
        // rename part-m-00000 to part-00000 (stems from > 0 tree merge iterations)
        final String dirPrefix = SolrOutputFormat.getOutputName(job);
        for (FileStatus stats : reducersPath.getFileSystem(getConf()).listStatus(reducersPath)) {
            Path srcPath = stats.getPath();
            if (stats.isDirectory() && srcPath.getName().startsWith(dirPrefix)) {
                String dstName = dirPrefix + srcPath.getName().substring(dirPrefix.length() + "-m".length());
                Path dstPath = new Path(srcPath.getParent(), dstName);
                if (!rename(srcPath, dstPath, srcPath.getFileSystem(getConf()))) {
                    return -1;
                }
            }
        }

        // move output to "results" directory
        if (!rename(reducersPath, new Path(input, "results"), reducersPath.getFileSystem(getConf()))) {
            return -1;
        }

        return 0;
    }


    private boolean renameTreeMergeShardDirs(Path reducersPath, Job job, FileSystem fs) throws IOException {
        final String dirPrefix = SolrOutputFormat.getOutputName(job);
        FileStatus[] dirs = reducersPath.getFileSystem(getConf()).listStatus(reducersPath, path -> path.getName().startsWith(dirPrefix));
        for (FileStatus dir : dirs) {
            if (!dir.isDirectory()) {
                throw new IllegalStateException("Not a directory: " + dir.getPath());
            }
        }

        // Example: rename part-m-00004 to _part-m-00004
        for (FileStatus dir : dirs) {
            Path path = dir.getPath();
            Path renamedPath = new Path(path.getParent(), "_" + path.getName());
            if (!rename(path, renamedPath, fs)) {
                return false;
            }
        }

        // Example: rename _part-m-00004 to part-m-00002
        for (FileStatus dir : dirs) {
            Path path = dir.getPath();
            Path renamedPath = new Path(path.getParent(), "_" + path.getName());

            // read auxiliary metadata file (per task) that tells which taskId
            // processed which split# aka solrShard
            Path solrShardNumberFile = new Path(renamedPath, TreeMergeMapper.SOLR_SHARD_NUMBER);
            InputStream in = fs.open(solrShardNumberFile);
            byte[] bytes = ByteStreams.toByteArray(in);
            in.close();
            Preconditions.checkArgument(bytes.length > 0);
            int solrShard = Integer.parseInt(new String(bytes, StandardCharsets.UTF_8));
            if (!delete(solrShardNumberFile, false, fs)) {
                return false;
            }

            // same as FileOutputFormat.NUMBER_FORMAT
            NumberFormat numberFormat = NumberFormat.getInstance(Locale.ENGLISH);
            numberFormat.setMinimumIntegerDigits(5);
            numberFormat.setGroupingUsed(false);
            Path finalPath = new Path(renamedPath.getParent(), dirPrefix + "-m-" + numberFormat.format(solrShard));

            log.info("MTree merge renaming solr shard: " + solrShard + " from dir: " + dir.getPath() + " to dir: " + finalPath);
            if (!rename(renamedPath, finalPath, fs)) {
                return false;
            }
        }
        return true;
    }


    private int createTreeMergeInputList(Path reducersPath, Job job, Path inputListPath) throws IOException {
        FileStatus[] dirs = listReducerDirs(reducersPath, job);
        int numDirs = 0;
        try (FSDataOutputStream out = inputListPath.getFileSystem(getConf()).create(inputListPath);
             Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            for (FileStatus stat : dirs) {
                if (log.isDebugEnabled()) {
                    log.debug("Adding input path {}", stat.getPath());
                }
                Path inputDir = new Path(stat.getPath(), "data/index");
                if (!inputDir.getFileSystem(getConf()).isDirectory(inputDir)) {
                    throw new IllegalStateException("Directory " + inputDir + " does not exist.");
                }
                writer.write(inputDir.toString() + "\n");
                numDirs++;
            }
        }
        return numDirs;
    }


    private FileStatus[] listReducerDirs(Path reducersPath, Job job) throws IOException {
        String dirPrefix = SolrOutputFormat.getOutputName(job);
        FileStatus[] dirs = reducersPath.getFileSystem(getConf())
                .listStatus(reducersPath, path -> path.getName().startsWith(dirPrefix));
        for (FileStatus dir : dirs) {
            if (!dir.isDirectory()) {
                throw new IllegalStateException("Path " + dir + " is not a directory.");
            }
        }
        // use alphanumeric sort (rather than lexicographical sort) to properly handle more than 99999 shards
        Arrays.sort(dirs, (f1, f2) -> new AlphaNumericComparator().compare(f1.getPath().getName(), f2.getPath().getName()));
        return dirs;
    }


    private boolean rename(Path src, Path dst, FileSystem fs) throws IOException {
        boolean success = fs.rename(src, dst);
        if (!success) {
            log.error("Cannot rename " + src + " to " + dst);
        }
        return success;
    }


    private boolean delete(Path path, boolean recursive, FileSystem fs) throws IOException {
        boolean success = fs.delete(path, recursive);
        if (!success) {
            log.error("Cannot delete " + path);
        }
        return success;
    }
}
