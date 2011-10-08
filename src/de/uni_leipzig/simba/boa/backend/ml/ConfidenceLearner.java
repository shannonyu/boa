package de.uni_leipzig.simba.boa.backend.ml;

import org.encog.neural.networks.ContainsFlat;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import org.encog.engine.network.activation.ActivationSigmoid;
import org.encog.ml.data.MLData;
import org.encog.ml.data.MLDataPair;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.data.basic.BasicMLData;
import org.encog.ml.data.basic.BasicMLDataSet;
import org.encog.neural.networks.BasicNetwork;
import org.encog.neural.networks.layers.BasicLayer;
import org.encog.neural.networks.training.Train;
import org.encog.neural.networks.training.propagation.resilient.ResilientPropagation;
import org.encog.util.obj.SerializeObject;
import org.encog.util.simple.EncogUtility;

import cern.colt.Arrays;

import de.uni_leipzig.simba.boa.backend.configuration.NLPediaSettings;
import de.uni_leipzig.simba.boa.backend.configuration.NLPediaSetup;

/**
 * 
 * @author ngonga
 */
public class ConfidenceLearner {

	BasicNetwork network;
	public double maxError = 0.2;
	public double errorDecrement = 0.05;
	public double minError = 0.05;
	public double maxHiddenToInputRatio = 3;
	public int maxEpochs = 10000;

	private static final String NETWORK_FILE = NLPediaSettings.getInstance().getSetting("neuronal.network.network.file");
	private static final String LEARN_FILE = NLPediaSettings.getInstance().getSetting("neuronal.network.learn.file");
	private static final String EVAL_OUTPUT_FILE = NLPediaSettings.getInstance().getSetting("neuronal.network.eval.output.file");
	private static final Integer N_FOLD_CROSS_VALIDATION = Integer.valueOf(NLPediaSettings.getInstance().getSetting("neuronal.network.n.fold.cross.validation"));

	/**
	 * Default constructor
	 * 
	 */
	public ConfidenceLearner() {

		if (!new File(NETWORK_FILE).exists()) {

			runEval(LEARN_FILE, EVAL_OUTPUT_FILE, NETWORK_FILE, N_FOLD_CROSS_VALIDATION);
		}
		else {

			network = getNetwork(NETWORK_FILE);
		}
	}

	/**
	 * Constructor. If a network file exists, then network is read out of it.
	 * Else the training data is used to train the best possible network
	 * 
	 * @param inputFile
	 *            Tab-separated input file; last column is 1 = good or 0 false
	 * @param outputFile
	 *            Output file for evaluation
	 * @param networkFile
	 * @param n
	 *            n for n-fold
	 * @param repetitions
	 *            number of repetitions of an experiments
	 */
	public ConfidenceLearner(String inputFile, String outputFile, String networkFile, int n, int repetitions) {

		if (!new File(networkFile).exists()) {

			runEval(inputFile, outputFile, networkFile, n);
		}
		else {

			network = getNetwork(networkFile);
		}
	}

	/**
	 * Runs n-fold cross-evaluation for an input file. Serializes best network
	 * to network file. Writes eval results to outputFile. Repeats each
	 * experiments once.
	 * 
	 * @param inputFile
	 *            Tab-separated input file; last column is 1 = good or 0 false
	 * @param outputFile
	 *            Output file for evaluation
	 * @param networkFile
	 * @param n
	 *            n for n-fold
	 */
	public void runEval(String inputFile, String outputFile, String networkFile, int n) {

		runEval(inputFile, outputFile, networkFile, n, 1);
	}

	/**
	 * Runs n-fold cross-evaluation for an input file. Serializes best network
	 * to network file. Writes eval results to outputFile.
	 * 
	 * @param inputFile
	 *            Tab-separated input file; last column is 1 = good or 0 false
	 * @param outputFile
	 *            Output file for evaluation
	 * @param networkFile
	 * @param n
	 *            n for n-fold
	 * @param repetitions
	 *            number of repetitions of an experiments
	 */
	public void runEval(String inputFile, String outputFile, String networkFile, int n, int repetitions) {

		MLDataSet[] allData = getData(inputFile, n);

		double bestAccuracy, accuracy;
		StringBuffer output = new StringBuffer();
		StringBuffer summary = new StringBuffer();
		int inputSize = allData[0].getInputSize() - 2;

		// create header for eval table
		output.append("Data Set\tHidden Size\tError Threshold");
		for (int i = 0; i < repetitions; i++) {
			output.append("\tAccuracy" + i);
		}
		output.append("\n");

		// create header for summarized results
		summary.append("Error Threshold");
		for (int hidden = inputSize; hidden < maxHiddenToInputRatio * inputSize + 1; hidden++) {
			summary.append("\tHidden Size = " + hidden);
		}
		summary.append("\n");
		// remember best confidence for serialization
		bestAccuracy = 0;
		double avgAcc;

		for (double error = maxError; error >= minError; error = error - errorDecrement) {
			summary.append(error + "\t");
			for (int hidden = inputSize; hidden < maxHiddenToInputRatio * inputSize + 1; hidden++) {

				avgAcc = 0;
				for (int i = 0; i < n; i++) {
					MLDataSet trainingData = merge(allData, i, n);
					MLDataSet testData = allData[i];
					output.append(i + "\t" + hidden + "\t" + error);

					for (int rep = 0; rep < repetitions; rep++) {
						accuracy = trainAndEvaluate(trainingData, testData, inputSize, hidden, 1, error);
						if (accuracy > bestAccuracy) {
							try {
								SerializeObject.save(new File(networkFile), network);
							}
							catch (Exception e) {
								e.printStackTrace();
							}
							bestAccuracy = accuracy;
						}
						output.append("\t" + accuracy * 100 + "%");
						avgAcc = avgAcc + accuracy;
					}
					output.append("\n");
				}
				avgAcc = avgAcc / (double) (n * repetitions);
				summary.append(avgAcc + "\t");
			}
			summary.append("\n");
		}
		try {
			PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outputFile)));
			writer.println(summary + "\n\n");
			writer.println(output);
			writer.close();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Merge all data included in alldata with the exception of nth one to
	 * training data The nth data set is used for testing
	 * 
	 * @param allData
	 *            All data available
	 * @param exception
	 *            Data set to use for testing
	 * @param n
	 *            Number of slices to creates (n-fold cross evaluation)
	 * @return Training data
	 */
	public MLDataSet merge(MLDataSet[] allData, int exception, int n) {

		MLDataSet result = new BasicMLDataSet();
		for (int i = 0; i < n; i++) {
			if (i != exception) {
				for (MLDataPair pair : allData[i]) {
					result.add(pair);
				}
			}
		}
		// System.out.println("Got " + result.getRecordCount() +
		// " records for training");
		return result;
	}

	/**
	 * Fetches data from a file and split this data into n slices for a n-fold
	 * cross evaluation
	 * 
	 * @param file
	 *            File containing the data
	 * @param n
	 *            Number of slices
	 * @return n data sets
	 */
	public MLDataSet[] getData(String file, int n) {

		// split input data into n data sets
		MLDataSet data[] = new BasicMLDataSet[n];
		for (int i = 0; i < n; i++) {
			data[i] = new BasicMLDataSet();
		}
		try {
			BufferedReader reader = new BufferedReader(new FileReader(file));
			String s = reader.readLine();
			int counter = 0;
			while (s != null) {
				String split[] = s.split("\t");
				MLData entry = new BasicMLData(split.length - 1);
				for (int i = 2; i < split.length - 1; i++) {
					entry.add(i, Double.parseDouble(split[i]));
				}
				// maps each entry to the expected value
				BasicMLData ideal = new BasicMLData(1);
				ideal.add(0, new Double(split[split.length - 1]));
				data[counter % n].add(entry, ideal);
				s = reader.readLine();
				counter++;
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return data;
	}

	/**
	 * Get a data set from a file
	 * 
	 * @param file
	 *            Input file
	 * @return Data set
	 */
	public MLDataSet getData(String file) {

		MLDataSet data = new BasicMLDataSet();
		try {
			BufferedReader reader = new BufferedReader(new FileReader(file));
			String s = reader.readLine();
			while (s != null) {
				String split[] = s.split("\t");
				MLData entry = new BasicMLData(split.length - 1);
				for (int i = 0; i < split.length - 1; i++) {
					entry.add(i, Double.parseDouble(split[i]));
				}
				// maps each entry to the expected value
				BasicMLData ideal = new BasicMLData(1);
				ideal.add(0, new Double(split[split.length - 1]));
//				System.out.println(entry + " -> " + ideal);
				data.add(entry, ideal);
				s = reader.readLine();
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return data;
	}

	/**
	 * Generates a network with one hidden layer
	 * 
	 * @param inputSize
	 *            Input size
	 * @param hiddenSize
	 *            Size of hidden layer
	 * @param outputSize
	 *            Output size
	 * @return Neural network
	 */
	public BasicNetwork generateBasicNetwork(int inputSize, int hiddenSize, int outputSize) {

		BasicNetwork network = new BasicNetwork();
		network.addLayer(new BasicLayer(null, true, inputSize));
		network.addLayer(new BasicLayer(new ActivationSigmoid(), true, hiddenSize));
		network.addLayer(new BasicLayer(new ActivationSigmoid(), false, outputSize));
		network.getStructure().finalizeStructure();
		network.reset();
		return network;
	}

	/**
	 * Generates a network with 2 hidden layers (not needed I suppose)
	 * 
	 * @param inputSize
	 *            Size of input layer
	 * @param hiddenSize1
	 *            Size of first hidden layer
	 * @param hiddenSize2
	 *            Size of second hidden layer
	 * @param outputSize
	 *            Size of output layer
	 * @return Network
	 */
	public BasicNetwork generateHiddenNetwork(int inputSize, int hiddenSize1, int hiddenSize2, int outputSize) {

		BasicNetwork network = new BasicNetwork();
		network.addLayer(new BasicLayer(null, true, inputSize));
		network.addLayer(new BasicLayer(new ActivationSigmoid(), true, hiddenSize1));
		network.addLayer(new BasicLayer(new ActivationSigmoid(), true, hiddenSize2));
		network.addLayer(new BasicLayer(new ActivationSigmoid(), false, outputSize));
		network.getStructure().finalizeStructure();
		network.reset();
		return network;
	}

	/**
	 * @deprecated
	 * 
	 * @param inputDataFile
	 * @param inputSize
	 * @param hiddenSize
	 * @param outputSize
	 * @param outputFile
	 * @param error
	 * @return
	 */
	public BasicNetwork trainToFile(String inputDataFile, int inputSize, int hiddenSize, int outputSize, String outputFile, double error) {

		BasicNetwork network = generateBasicNetwork(inputSize, hiddenSize, outputSize);
		MLDataSet dataSet = getData(inputDataFile);
		// train
		EncogUtility.trainToError(network, dataSet, error);
		// evaluate
		EncogUtility.evaluate(network, dataSet);
		try {
			SerializeObject.save(new File(outputFile), network);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return network;
	}

	/**
	 * Returns the number of misclassified patterns
	 * 
	 * @param trainingData
	 *            Training data
	 * @param testData
	 *            Test data
	 * @param inputSize
	 *            Size of input layer
	 * @param hiddenSize
	 *            Size of hidden layer
	 * @param outputSize
	 *            Size of output layer
	 * @param error
	 *            Error to train to
	 * @return Percentage of correctly classified patterns from test data
	 */
	public double trainAndEvaluate(MLDataSet trainingData, MLDataSet testData, int inputSize, int hiddenSize, int outputSize, double error) {

		network = generateBasicNetwork(inputSize, hiddenSize, outputSize);
		// train
		// default
		// EncogUtility.trainToError(network, trainingData, error);
		Train train = new ResilientPropagation((ContainsFlat) network, trainingData);
		trainToError(train, error, maxEpochs);

		// evaluate
		double count = (double) testData.getRecordCount();
		for (MLDataPair pair : testData) {
			MLData result = network.compute(pair.getInput());
			double value = 0;
			if (result.getData(0) > 0.5) {
				value = 1;
			}
			// System.out.println(pair.getIdeal().getData(0) + " -> " + value);
			if (pair.getIdeal().getData(0) != value) {
				count--;
			}
		}
		return count / (double) testData.getRecordCount();
	}

	/**
	 * Runs the training of a neural networks. Training runs until the error
	 * threshold or the maximal number of epochs are reacher
	 * 
	 * @param train
	 *            Training data
	 * @param network
	 *            Network to train
	 * @param error
	 *            Error threshold
	 * @param maxEpochs
	 *            Maximal number of epochs
	 */
	public static void trainToError(final Train train, final double error, final int maxEpochs) {

		int epoch = 1;
		// System.out.println("Beginning training...");

		do {
			train.iteration();

			// System.out.println("Iteration #" + Format.formatInteger(epoch)
			// + " Error:" + Format.formatPercent(train.getError())
			// + " Target Error: " + Format.formatPercent(error));
			epoch++;
		}
		while ((train.getError() > error) && !train.isTrainingDone() && (epoch <= maxEpochs));
		train.finishTraining();
	}

	/**
	 * Transform a string into a data entry
	 * 
	 * @param s
	 *            String input
	 * @return Data entry
	 */
	public static MLData getSingleEntry(String s) {

		String split[] = s.split("\t");
		MLData data = new BasicMLData(split.length);
		for (int i = 2; i < split.length; i++) {
			data.add(i, Double.parseDouble(split[i]));
		}
		return data;
	}

	/**
	 * Computes the confidence for a given network in a file and a data entry
	 * 
	 * @param netFile
	 *            File
	 * @param data
	 *            Machine Learning Data
	 * @return Score
	 */
	public double getScore(String netFile, MLData data) {

		double score = 0;
		try {
			BasicNetwork net = (BasicNetwork) SerializeObject.load(new File(netFile));
			MLData result = network.compute(data);
			return result.getData(0);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return score;
	}

	/**
	 * Fetches a network from a file
	 * 
	 * @param netFile
	 *            Input file
	 * @return Network
	 */
	public static BasicNetwork getNetwork(String netFile) {

		try {
			// return (BasicNetwork)EncogDirectoryPersistence.loadObject(new
			// File(netFile));
			return (BasicNetwork) SerializeObject.load(new File(netFile));
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Computes the score of an entry given a network
	 * 
	 * @param net
	 *            Basic network
	 * @param entry
	 *            Data entry
	 * @return Confidence score
	 */
	public static double getConfidence(BasicNetwork net, String entry) {

		MLData data = getSingleEntry(entry);
		return net.compute(data).getData(0);
	}

	/**
	 * Computes the score of an entry by using the network in the confidence
	 * learner
	 * 
	 * @param entry
	 *            Entry
	 * @return Confidence
	 */
	public double getConfidence(String entry) {

		MLData data = getSingleEntry(entry);
		return network.compute(data).getData(0);
	}

	public static void main(String args[]) {

		ConfidenceLearner dr = new ConfidenceLearner();

		String input = "0.0\t0.0\t0.0\t0.0\t0.01866977829638273\t0.0\t0.0\t0.0016778523489932886\t0.039603960396039604";

		System.out.println(dr.getConfidence(input));
	}
}