import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import java.io.*;
import java.util.*;

public class cid3 implements Serializable{
    enum AttributeType {Discrete, Continuous, Ignore}
    int numAttributes;		// The number of attributes including the output attribute
    String[] attributeNames;	// The names of all attributes.  It is an array of dimension numAttributes.  The last attribute is the output attribute
    AttributeType[] attributeTypes;
    private int classAttribute;
    double[] meanValues;
    int[] mostCommonValues;
    String fileName;
    long seed = 13579;
    int maxThreads = 500;
    transient ArrayList<Thread> globalThreads = new ArrayList<>();
    /* Possible values for each attribute is stored in a vector.  domains is an array of dimension numAttributes.
        Each element of this array is a vector that contains values for the corresponding attribute
        domains[0] is a vector containing the values of the 0-th attribute, etc..
        The last attribute is the output attribute
    */
    HashMap<Integer,Object> [] domainsIndexToValue;
    HashMap<Object,Integer> [] domainsValueToIndex;
    enum Criteria {Entropy, Certainty, Gini}
    /*  The class to represent a data point consisting of numAttributes values of attributes  */
    class DataPoint implements Serializable{

        /* The values of all attributes stored in this array.  i-th element in this array
           is the index to the element in the vector domains representing the symbolic value of
           the attribute.  For example, if attributes[2] is 1, then the actual value of the
           2-nd attribute is obtained by domains[2].get(1).  This representation makes
           comparing values of attributes easier - it involves only integer comparison and
           no string comparison.
           The last attribute is the output attribute
        */
        public int []attributes;

        public DataPoint(int numattributes) {
            attributes = new int[numattributes];
        }
    }

    //This class will be used to calculate all probabilities in one pass.
    public class Probabilities implements Serializable{
        int attribute;
        double[] prob;
        double[][] probCandA;
        double[][] probCgivenA;

        public Probabilities(int att){
            attribute = att;
            prob = new double[domainsIndexToValue[attribute].size()];
            probCandA = new double[domainsIndexToValue[attribute].size()][domainsIndexToValue[classAttribute].size()];
            probCgivenA = new double[domainsIndexToValue[attribute].size()][domainsIndexToValue[classAttribute].size()];
        }
    }
    //This is an utility class to return the information and threshold of continuous attributes.
    public class Certainty implements Serializable{
        Double certainty;
        Double threshold;

        public Certainty(double info, double thresh){
            certainty = info;
            threshold = thresh;
        }
    }

    transient ArrayList<DataPoint> testData = new ArrayList<>();
    transient ArrayList<DataPoint> trainData = new ArrayList<>();
    transient ArrayList<DataPoint>[] crossValidationChunks = new ArrayList[10];
    transient boolean testDataExists = false;
    transient boolean splitTrainData = false;
    transient boolean isRandomForest = false;
    transient boolean isCrossValidation = false;
    transient int numberOfTrees = 1;
    /* The class to represent a node in the decomposition tree.
     */
    class TreeNode implements Serializable{
        public double certaintyUsedToDecompose = 0;
        public transient ArrayList<DataPoint> data;			// The set of data points if this is a leaf node
        public int[] frequencyClasses;          //This is for saving time when calculating most common class
        public int decompositionAttribute;	// If this is not a leaf node, the attribute that is used to divide the set of data points
        public int decompositionValue;		// the attribute-value that is used to divide the parent node
        public String decompositionValueContinuous = "";
        public String decompositionValueContinuousSymbol = "";
        public double thresholdContinuous = 0;
        public ArrayList<TreeNode> children;		// If this is not a leaf node, references to the children nodes
        public TreeNode parent;			// The parent to this node.  The root has parent == null

        public TreeNode() {
            data = new ArrayList<>();
        }
    }

    /*  The root of the decomposition tree  */
    TreeNode root = new TreeNode();
    ArrayList<TreeNode> rootsRandomForest = new ArrayList<>();
    transient ArrayList<TreeNode>[] cvRandomForests = new ArrayList[10];
    transient ArrayList<TreeNode> rootsCrossValidation = new ArrayList<>();
    transient Criteria criteria = Criteria.Certainty;
    transient int totalRules = 0;
    transient int totalNodes = 0;
    /*  This function returns an integer corresponding to the symbolic value of the attribute.
        If the symbol does not exist in the domain, the symbol is added to the domain of the attribute
    */
    public int getSymbolValue(int attribute, Object symbol) {
        Integer index = domainsValueToIndex[attribute].get(symbol);
        if (index == null) {
            if (domainsIndexToValue[attribute].isEmpty()){
                domainsIndexToValue[attribute].put(0, symbol);
                domainsValueToIndex[attribute].put(symbol, 0);
                return 0;
            }
            else {
                int size = domainsIndexToValue[attribute].size();
                domainsIndexToValue[attribute].put(size, symbol);
                domainsValueToIndex[attribute].put(symbol, size);
                return size;
            }
        }
        return index;
    }

    // Returns the most common class for the specified node
    public int mostCommonFinal(TreeNode n){
        int numvaluesClass = domainsIndexToValue[classAttribute].size();
        int value = n.frequencyClasses[0];
        int result = 0;
        //if(n.frequencyClasses.length < numvaluesClass){
        //    String error = "Error";
        //}
        for(int i = 1; i < numvaluesClass; i++){
            if (n.frequencyClasses[i] > value){
                value = n.frequencyClasses[i];
                result = i;
            }
        }
        return result;
    }
    /*  Returns a subset of data, in which the value of the specfied attribute of all data points is the specified value  */
    public DataFrequencies getSubset(ArrayList<DataPoint> data, int attribute, int value) {
        ArrayList<DataPoint> subset = new ArrayList<>();
        int[] frequencies = new int[domainsIndexToValue[classAttribute].size()];
        int num = data.size();
        for (int i=0; i< num; i++) {
            DataPoint point = data.get(i);
            if (point.attributes[attribute] == value) {
                subset.add(point);
                frequencies[point.attributes[classAttribute]]++;
            }
        }
        return new DataFrequencies(subset, frequencies);
    }

    public int[] getFrequencies(ArrayList<DataPoint> data){
        int[] frequencies = new int[domainsIndexToValue[classAttribute].size()];
        int num = data.size();
        for (int i=0; i< num; i++) {
            DataPoint point = data.get(i);
            frequencies[point.attributes[classAttribute]]++;
        }
        return frequencies;
    }

    public Tuple<DataFrequencies,DataFrequencies> getSubsetsBelowAndAbove(ArrayList<DataPoint> data, int attribute, double value){
        ArrayList<DataPoint> subsetBelow = new ArrayList<>();
        ArrayList<DataPoint> subsetAbove = new ArrayList<>();
        int[] frequenciesBelow = new int[domainsIndexToValue[classAttribute].size()];
        int[] frequenciesAbove = new int[domainsIndexToValue[classAttribute].size()];
        int num = data.size();
        for (int i = 0; i < num; i++) {
            DataPoint point = data.get(i);
            if ((double)domainsIndexToValue[attribute].get(point.attributes[attribute]) <= value) {
                subsetBelow.add(point);
                frequenciesBelow[point.attributes[classAttribute]]++;
            }
            else {
                subsetAbove.add(point);
                frequenciesAbove[point.attributes[classAttribute]]++;
            }
        }
        return new Tuple<> (new DataFrequencies(subsetBelow, frequenciesBelow), new DataFrequencies(subsetAbove, frequenciesAbove));
    }

    //This is the final form of the certainty function.
    public Certainty calculateCertainty(ArrayList<DataPoint> data, int givenThatAttribute){

        int numdata = data.size();
        if (numdata == 0) return new Certainty(0,0);
        int numvaluesClass = domainsIndexToValue[classAttribute].size();
        int numvaluesgivenAtt = domainsIndexToValue[givenThatAttribute].size();

        //If attribute is discrete
        if (attributeTypes[givenThatAttribute] == AttributeType.Discrete){
            Probabilities[] probabilities = CalculateAllProbabilities(data);

            double sum, sum2 = 0;
            double probability, probabilityCandA;
            for (int j = 0; j < numvaluesgivenAtt; j++) {
                probability = probabilities[givenThatAttribute].prob[j];
                sum = 0;
                for (int i = 0; i < numvaluesClass; i++){
                    probabilityCandA = probabilities[givenThatAttribute].probCandA[j][i];
                    sum += Math.abs(probabilityCandA - 1.*probability/numvaluesClass);
                }

                sum2 += sum;
            }
            return new Certainty(sum2,0);
        }
        //If attribute is continuous.
        else{
            double finalThreshold = 0, totalCertainty = 0, finalTotalCertainty = 0;
            /*---------------------------------------------------------------------------------------------------------*/
            //Implementation of thresholds using a sorted set
            SortedSet<Double> attributeValuesSet = new TreeSet<>();
            HashMap<Double,Tuple<Integer,Boolean>> attributeToClass =  new HashMap<>();
            for (int i=0; i< numdata; i++){
                DataPoint point  = data.get(i);
                double attribute = (double)domainsIndexToValue[givenThatAttribute].get(point.attributes[givenThatAttribute]);
                int clas = point.attributes[classAttribute];
                attributeValuesSet.add(attribute);
                Tuple<Integer,Boolean> tuple = attributeToClass.get(attribute);
                if (tuple != null){
                    if (tuple.x != clas && tuple.y)
                        attributeToClass.put(attribute, new Tuple<>(clas,false));
                }
                else attributeToClass.put(attribute, new Tuple<>(clas,true));
            }

            Iterator<Double> it = attributeValuesSet.iterator();
            double attributeValue = it.next();
            Tuple<Integer,Boolean> attributeClass1 = attributeToClass.get(attributeValue);
            int theClass = attributeClass1.x;

            ArrayList<Threshold> thresholds = new ArrayList<>();
            while (it.hasNext()) {
                double attributeValue2 = it.next();
                Tuple<Integer,Boolean> attributeClass2 = attributeToClass.get(attributeValue2);
                int theClass2 = attributeClass2.x;
                if(theClass2 != theClass || !attributeClass2.y || !attributeClass1.y){
                    //Add threshold
                    double median = (attributeValue+attributeValue2)/2;
                    thresholds.add(new Threshold(median, new SumUnderAndOver[numvaluesClass]));
                }
                //Set new point
                attributeValue = attributeValue2;
                theClass = theClass2;
                attributeClass1 = attributeClass2;
            }

            /*---------------------------------------------------------------------------------------------------------*/
            //If there are no thresholds return zero.
            if (thresholds.isEmpty()) return new Certainty(0,0);

            //This trick reduces the possible thresholds to just ONE 0r TWO, dramatically improving running times!
            //=========================================================

            int centerThresholdIndex = thresholds.size()/2;
            Threshold centerThreshold, centerThreshold1;

            if (thresholds.size() == 1){
                centerThreshold = thresholds.get(0);
                thresholds.clear();
                thresholds.add(centerThreshold);
            }
            else
            if (thresholds.size() % 2 != 0){
                centerThreshold = thresholds.get(centerThresholdIndex);
                thresholds.clear();
                thresholds.add(centerThreshold);
            }
            else {
                centerThreshold = thresholds.get(centerThresholdIndex);
                centerThreshold1 = thresholds.get(centerThresholdIndex - 1);
                thresholds.clear();
                thresholds.add(centerThreshold);
                thresholds.add(centerThreshold1);
            }
            //=========================================================


            double probAUnder, probAOver, probCandAUnder, probCandAOver, certaintyUnder = 0, certaintyOver = 0;
            DataPoint point;
            //Loop through the data just one time
            for (int j = 0; j < numdata; j++){
                point = data.get(j);
                //For each threshold count data to get prob and probCandA
                int clas = point.attributes[classAttribute];
                for (int i = 0; i < thresholds.size(); i++){
                    Threshold iThreshold = thresholds.get(i);
                    if (iThreshold.sumsClassesAndAttribute[clas] == null) iThreshold.sumsClassesAndAttribute[clas] = new SumUnderAndOver(0,0);
                    if ((double)domainsIndexToValue[givenThatAttribute].get(point.attributes[givenThatAttribute]) <= iThreshold.value) {
                        iThreshold.sumAUnder++;
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[clas].under++;
                    }
                    else {
                        iThreshold.sumAOver++;
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[clas].over++;
                    }
                }
            }

            //Now calculate probabilities
            for (int i = 0; i < thresholds.size(); i++){
                //Calculate prob
                probAUnder = 1.*thresholds.get(i).sumAUnder/numdata;
                probAOver = 1.*thresholds.get(i).sumAOver/numdata;

                //Reset the certainty
                certaintyUnder = 0;
                certaintyOver = 0;

                for (int c = 0; c < numvaluesClass; c++){
                    if (thresholds.get(i).sumsClassesAndAttribute != null && thresholds.get(i).sumsClassesAndAttribute[c] != null){
                        probCandAUnder = 1.*thresholds.get(i).sumsClassesAndAttribute[c].under/numdata;
                        probCandAOver = 1.*thresholds.get(i).sumsClassesAndAttribute[c].over/numdata;
                    }

                    else {
                        probCandAUnder = 0;
                        probCandAOver = 0;
                    }

                    certaintyUnder += Math.abs(probCandAUnder - probAUnder/numvaluesClass);
                    certaintyOver += Math.abs(probCandAOver - probAOver/numvaluesClass);
                }
                //Calculate totals
                totalCertainty = certaintyUnder + certaintyOver;
                if (finalTotalCertainty < totalCertainty){
                    finalTotalCertainty = totalCertainty;
                    finalThreshold = thresholds.get(i).value;
                }
            }
            return new Certainty(finalTotalCertainty, finalThreshold);
            //*******************************************************************************************//
        }
    }

    //This is Entropy.
    public Certainty calculateEntropy(ArrayList<DataPoint> data, int givenThatAttribute){
        int numdata = data.size();
        if (numdata == 0) return new Certainty(0,0);
        int numvaluesClass = domainsIndexToValue[classAttribute].size();
        int numvaluesgivenAtt = domainsIndexToValue[givenThatAttribute].size();
        //If attribute is discrete
        if (attributeTypes[givenThatAttribute] == AttributeType.Discrete){
            Probabilities[] probabilities = CalculateAllProbabilities(data);
            double sum = 0, sum2 = 0;
            double probability, probabilityCgivenA;
            for (int j = 0; j < numvaluesgivenAtt; j++) {
                //probability = prob(data,givenThatAttribute,j);
                probability = probabilities[givenThatAttribute].prob[j];
                sum = 0;
                for (int i = 0; i < numvaluesClass; i++){
                    //probabilityCandA = probCandA(data, givenThatAttribute, j, i);
                    probabilityCgivenA = probabilities[givenThatAttribute].probCgivenA[j][i];
                    if (probabilityCgivenA != 0)
                        sum += -probabilityCgivenA * Math.log(probabilityCgivenA);
                }
                sum2 += probability * sum;
            }
            return new Certainty(sum2,0);
        }
        //If attribute is continuous.
        else{
            double finalThreshold = 0, totalEntropy = 0, finalTotalEntropy = 0;
            /*---------------------------------------------------------------------------------------------------------*/
            //Implementation of thresholds using a sorted set
            SortedSet<Double> attributeValuesSet = new TreeSet<>();
            HashMap<Double,Tuple<Integer,Boolean>> attributeToClass =  new HashMap<>();
            for (int i=0; i< numdata; i++){
                DataPoint point  = (DataPoint)data.get(i);
                double attribute = (double)domainsIndexToValue[givenThatAttribute].get(point.attributes[givenThatAttribute]);
                int clas = point.attributes[classAttribute];
                attributeValuesSet.add(attribute);
                Tuple<Integer,Boolean> tuple = attributeToClass.get(attribute);
                if (tuple != null){
                    if (tuple.x != clas && tuple.y)
                        attributeToClass.put(attribute, new Tuple<>(clas,false));
//                            else
//                                if (tuple.y)
//                                    attributeToClass.put(attribute, new Tuple(clas,true));
                }
                else attributeToClass.put(attribute, new Tuple<>(clas,true));
            }
            Iterator<Double> it = attributeValuesSet.iterator();
            double attributeValue = it.next();
            Tuple<Integer,Boolean> attributeClass1 = attributeToClass.get(attributeValue);
            int theClass = attributeClass1.x;
            ArrayList<Threshold> thresholds = new ArrayList<>();
            while (it.hasNext()) {
                double attributeValue2 = it.next();
                Tuple<Integer,Boolean> attributeClass2 = attributeToClass.get(attributeValue2);
                int theClass2 = attributeClass2.x;
                if(theClass2 != theClass || !attributeClass2.y || !attributeClass1.y){
                    //Add threshold
                    double median = (attributeValue+attributeValue2)/2;
                    thresholds.add(new Threshold(median, new SumUnderAndOver[numvaluesClass]));
                }
                //Set new point
                attributeValue = attributeValue2;
                theClass = theClass2;
                attributeClass1 = attributeClass2;
            }
            /*---------------------------------------------------------------------------------------------------------*/
            //If there are no thresholds return -1.
            if (thresholds.isEmpty()) return new Certainty(-1,0);
            //This trick reduces the possible thresholds to just ONE 0r TWO, dramatically improving running times!
            //=========================================================

            int centerThresholdIndex = thresholds.size()/2;
            Threshold centerThreshold, centerThreshold1;

            if (thresholds.size() == 1){
                centerThreshold = thresholds.get(0);
                thresholds.clear();
                thresholds.add(centerThreshold);
            }
            else
            if (thresholds.size() % 2 != 0){
                centerThreshold = thresholds.get(centerThresholdIndex);
                thresholds.clear();
                thresholds.add(centerThreshold);
            }
            else {
                centerThreshold = thresholds.get(centerThresholdIndex);
                centerThreshold1 = thresholds.get(centerThresholdIndex - 1);
                thresholds.clear();
                thresholds.add(centerThreshold);
                thresholds.add(centerThreshold1);
            }
            //=========================================================
            double probAUnder, probAOver, probCandAUnder, probCandAOver, entropyUnder = 0, entropyOver = 0;
            boolean selected = false;
            DataPoint point;
            //Loop through the data just one time
            for (int j = 0; j < numdata; j++){
                point = data.get(j);
                //For each threshold count data to get prob and probCandA
                int clas = point.attributes[classAttribute];
                for (int i = 0; i < thresholds.size(); i++){
                    Threshold iThreshold = thresholds.get(i);
                    //if (thresholds[i].sumsClassesAndAttribute == null) thresholds[i].sumsClassesAndAttribute = new SumUnderAndOver[numvaluesClass];
                    if (iThreshold.sumsClassesAndAttribute[clas] == null) iThreshold.sumsClassesAndAttribute[clas] = new SumUnderAndOver(0,0);
                    if ((double)domainsIndexToValue[givenThatAttribute].get(point.attributes[givenThatAttribute]) < iThreshold.value) {
                        iThreshold.sumAUnder++;
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[clas].under++;
                    }
                    else {
                        iThreshold.sumAOver++;
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[clas].over++;
                    }
                }
            }
            //Now calculate probabilities
            for (int i = 0; i < thresholds.size(); i++){
                //Calculate prob
                probAUnder = 1.*thresholds.get(i).sumAUnder/numdata;
                probAOver = 1.*thresholds.get(i).sumAOver/numdata;
                //Reset the entropy
                entropyUnder = 0;
                entropyOver = 0;
                for (int c = 0; c < numvaluesClass; c++){
                    if (thresholds.get(i).sumsClassesAndAttribute != null && thresholds.get(i).sumsClassesAndAttribute[c] != null){
                        probCandAUnder = 1.*thresholds.get(i).sumsClassesAndAttribute[c].under/numdata;
                        probCandAOver = 1.*thresholds.get(i).sumsClassesAndAttribute[c].over/numdata;
                    }
                    else {
                        probCandAUnder = 0;
                        probCandAOver = 0;
                    }
                    if (probCandAUnder != 0 && probAUnder != 0)
                        entropyUnder += -probCandAUnder/probAUnder * Math.log(probCandAUnder/probAUnder);
                    if (probCandAOver != 0 && probAOver != 0)
                        entropyOver += -probCandAOver/probAOver * Math.log(probCandAOver/probAOver);
                }
                //Calculate totals
                totalEntropy = entropyUnder * probAUnder + entropyOver * probAOver;
                if (!selected) {
                    selected = true;
                    finalTotalEntropy = totalEntropy;
                    finalThreshold = thresholds.get(i).value;
                }
                else {
                    if (finalTotalEntropy > totalEntropy) {
                        finalTotalEntropy = totalEntropy;
                        finalThreshold = thresholds.get(i).value;
                    }
                }
//                        if (finalTotalEntropy > totalEntropy){
//                                finalTotalEntropy = totalEntropy;
//                                finalThreshold = thresholds.get(i).value;
//                            }
            }
            return new Certainty(finalTotalEntropy, finalThreshold);
            //*******************************************************************************************//
        }
    }

    //This is Gini.
    public Certainty calculateGini(ArrayList<DataPoint> data, int givenThatAttribute){
        int numdata = data.size();
        if (numdata == 0) return new Certainty(0,0);
        int numvaluesClass = domainsIndexToValue[classAttribute].size();
        int numvaluesgivenAtt = domainsIndexToValue[givenThatAttribute].size();
        //If attribute is discrete
        if (attributeTypes[givenThatAttribute] == AttributeType.Discrete){
            Probabilities[] probabilities = CalculateAllProbabilities(data);
            double sum = 0, sum2 = 0;
            double probability, probabilityCgivenA, gini;
            for (int j = 0; j < numvaluesgivenAtt; j++) {
                //probability = prob(data,givenThatAttribute,j);
                probability = probabilities[givenThatAttribute].prob[j];
                sum = 0;
                for (int i = 0; i < numvaluesClass; i++){
                    //probabilityCandA = probCandA(data, givenThatAttribute, j, i);
                    probabilityCgivenA = probabilities[givenThatAttribute].probCgivenA[j][i];
                    sum += Math.pow(probabilityCgivenA,2);
                }
                gini = 1 - sum;
                sum2 += probability * gini;
            }
            return new Certainty(sum2,0);
        }
        //If attribute is continuous.
        else{
            double finalThreshold = 0, totalGini = 0, finalTotalGini = 0;
            /*---------------------------------------------------------------------------------------------------------*/
            //Implementation of thresholds using a sorted set
            SortedSet<Double> attributeValuesSet = new TreeSet<>();
            HashMap<Double,Tuple<Integer,Boolean>> attributeToClass =  new HashMap<>();
            for (int i=0; i< numdata; i++){
                DataPoint point  = data.get(i);
                double attribute = (double)domainsIndexToValue[givenThatAttribute].get(point.attributes[givenThatAttribute]);
                int clas = point.attributes[classAttribute];
                attributeValuesSet.add(attribute);
                Tuple<Integer,Boolean> tuple = attributeToClass.get(attribute);
                if (tuple != null){
                    if (tuple.x != clas && tuple.y)
                        attributeToClass.put(attribute, new Tuple<>(clas,false));
//                            else
//                                if (tuple.y)
//                                    attributeToClass.put(attribute, new Tuple(clas,true));
                }
                else attributeToClass.put(attribute, new Tuple<>(clas,true));
            }
            Iterator<Double> it = attributeValuesSet.iterator();
            double attributeValue = it.next();
            Tuple<Integer,Boolean> attributeClass1 = attributeToClass.get(attributeValue);
            int theClass = attributeClass1.x;
            ArrayList<Threshold> thresholds = new ArrayList<>();
            while (it.hasNext()) {
                double attributeValue2 = it.next();
                Tuple<Integer,Boolean> attributeClass2 = attributeToClass.get(attributeValue2);
                int theClass2 = attributeClass2.x;
                if(theClass2 != theClass || !attributeClass2.y || !attributeClass1.y){
                    //Add threshold
                    double median = (attributeValue+attributeValue2)/2;
                    thresholds.add(new Threshold(median, new SumUnderAndOver[numvaluesClass]));
                }
                //Set new point
                attributeValue = attributeValue2;
                theClass = theClass2;
                attributeClass1 = attributeClass2;
            }
            /*---------------------------------------------------------------------------------------------------------*/
            //If there are no thresholds return -1.
            if (thresholds.isEmpty()) return new Certainty(-1,0);
            //This trick reduces the possible thresholds to just ONE 0r TWO, dramatically improving running times!
            //=========================================================

            int centerThresholdIndex = thresholds.size()/2;
            Threshold centerThreshold, centerThreshold1;

            if (thresholds.size() == 1){
                centerThreshold = thresholds.get(0);
                thresholds.clear();
                thresholds.add(centerThreshold);
            }
            else
            if (thresholds.size() % 2 != 0){
                centerThreshold = thresholds.get(centerThresholdIndex);
                thresholds.clear();
                thresholds.add(centerThreshold);
            }
            else {
                centerThreshold = thresholds.get(centerThresholdIndex);
                centerThreshold1 = thresholds.get(centerThresholdIndex - 1);
                thresholds.clear();
                thresholds.add(centerThreshold);
                thresholds.add(centerThreshold1);
            }
            //=========================================================
            double probAUnder, probAOver, probCandAUnder, probCandAOver, giniUnder = 0, giniOver = 0;
            boolean selected = false;
            DataPoint point;
            //Loop through the data just one time
            for (int j = 0; j < numdata; j++){
                point = data.get(j);
                //For each threshold count data to get prob and probCandA
                int clas = point.attributes[classAttribute];
                for (int i = 0; i < thresholds.size(); i++){
                    Threshold iThreshold = thresholds.get(i);
                    //if (thresholds[i].sumsClassesAndAttribute == null) thresholds[i].sumsClassesAndAttribute = new SumUnderAndOver[numvaluesClass];
                    if (iThreshold.sumsClassesAndAttribute[clas] == null) iThreshold.sumsClassesAndAttribute[clas] = new SumUnderAndOver(0,0);
                    if ((double)domainsIndexToValue[givenThatAttribute].get(point.attributes[givenThatAttribute]) < iThreshold.value) {
                        iThreshold.sumAUnder++;
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[clas].under++;
                    }
                    else {
                        iThreshold.sumAOver++;
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[clas].over++;
                    }
                }
            }
            //Now calculate probabilities
            for (int i = 0; i < thresholds.size(); i++){
                //Calculate prob
                probAUnder = 1.*thresholds.get(i).sumAUnder/numdata;
                probAOver = 1.*thresholds.get(i).sumAOver/numdata;
                //Reset the gini
                giniUnder = 0;
                giniOver = 0;
                for (int c = 0; c < numvaluesClass; c++){
                    if (thresholds.get(i).sumsClassesAndAttribute != null && thresholds.get(i).sumsClassesAndAttribute[c] != null){
                        probCandAUnder = 1.*thresholds.get(i).sumsClassesAndAttribute[c].under/numdata;
                        probCandAOver = 1.*thresholds.get(i).sumsClassesAndAttribute[c].over/numdata;
                    }
                    else {
                        probCandAUnder = 0;
                        probCandAOver = 0;
                    }
                    giniUnder += Math.pow(probCandAUnder/probAUnder,2);
                    giniOver += Math.pow(probCandAOver/probAOver,2);
                }
                //Calculate totals
                giniUnder = 1 - giniUnder;
                giniOver = 1 - giniOver;
                totalGini = giniUnder * probAUnder + giniOver * probAOver;
                if (!selected) {
                    selected = true;
                    finalTotalGini = totalGini;
                    finalThreshold = thresholds.get(i).value;
                }
                else {
                    if (finalTotalGini > totalGini) {
                        finalTotalGini = totalGini;
                        finalThreshold = thresholds.get(i).value;
                    }
                }
//                        if (finalTotalGini > totalGini){
//                                finalTotalGini= totalGini;
//                                finalThreshold = thresholds.get(i).value;
//                            }
            }
            return new Certainty(finalTotalGini, finalThreshold);
            //*******************************************************************************************//
        }
    }

    //This method calculates all probabilities in one run
    public Probabilities[] CalculateAllProbabilities(ArrayList<DataPoint> data){
        int numdata = data.size();
        Probabilities[] probabilities = new Probabilities[numAttributes - 1];

        //Initialize the array
        for (int j = 0; j < numAttributes - 1; j++){
            if(attributeTypes[j] == AttributeType.Ignore) continue;
            Probabilities p = new Probabilities(j);
            probabilities[j] = p;
        }
        //Count occurrences
        for (int i = 0; i < numdata; i++)
        {
            DataPoint point = data.get(i);
            for (int j = 0; j < point.attributes.length - 1; j++){
                if (attributeTypes[j] == AttributeType.Ignore) continue;
                probabilities[j].prob[point.attributes[j]]= probabilities[j].prob[point.attributes[j]] + 1;
                probabilities[j].probCandA[point.attributes[j]][point.attributes[classAttribute]]= probabilities[j].probCandA[point.attributes[j]][point.attributes[classAttribute]] + 1 ;
            }
        }
        // Divide all values by total data size to get probabilities.
        Probabilities current;
        for (int i = 0; i < probabilities.length; i++){
            if (attributeTypes[i] == AttributeType.Ignore) continue;
            current = probabilities[i];
            for (int j = 0; j < current.prob.length; j++){
                current.prob[j] = current.prob[j]/numdata;
            }
            for (int j = 0; j < current.probCandA.length; j++){
                for (int k = 0; k < current.probCandA[j].length; k++){
                    current.probCandA[j][k] = current.probCandA[j][k]/numdata;
                }
            }
            //Calculate ProbCgivenA
            for (int j = 0; j < current.probCgivenA.length; j++){
                for (int k = 0; k < current.probCgivenA[j].length; k++){
                    current.probCgivenA[j][k] = current.probCandA[j][k]/current.prob[j];
                }
            }
        }
        return probabilities;
    }

    /*  This function checks if the specified attribute is used to decompose the data set
        in any of the parents of the specfied node in the decomposition tree.
        Recursively checks the specified node as well as all parents
    */
    public boolean alreadyUsedToDecompose(TreeNode node, int attribute) {
        if (node.children != null) {
            if (node.decompositionAttribute == attribute )
                return true;
        }
        if (node.parent == null) return false;
        return alreadyUsedToDecompose(node.parent, attribute);
    }
    public boolean stopConditionAllClassesEqualEfficient(int[] frequencyClasses){

        int numvaluesClass = domainsIndexToValue[classAttribute].size();
        boolean oneClassIsPresent = false;
        for (int i = 0; i < numvaluesClass; i++){
            if (frequencyClasses[i] != 0) {
                if (!oneClassIsPresent) oneClassIsPresent = true;
                else return false;
            }
        }
        return true;
    }

    /*  This function decomposes the specified node according to the id3 algorithm.
    Recursively divides all children nodes until it is not possible to divide any further  */
    public void decomposeNode(TreeNode node,  ArrayList<Integer> selectedAtts, long mySeed) {
        Certainty bestCertainty =  new Certainty(0,0);
        boolean selected=false;
        int selectedAttribute=0;

        if(criteria == Criteria.Certainty)
        {
            if (node.data == null || node.data.size() <= 1) return;
            if (stopConditionAllClassesEqualEfficient(node.frequencyClasses)) return;
            Certainty certainty;
            for (int i=0; i< selectedAtts.size(); i++) {
                if ( classAttribute == selectedAtts.get(i) ) continue;
                if (attributeTypes[selectedAtts.get(i)] == AttributeType.Discrete && alreadyUsedToDecompose(node, selectedAtts.get(i))) continue;
                certainty =  calculateCertainty(node.data,selectedAtts.get(i));
                if (certainty.certainty == 0) continue;
                //Select best attribute
                if (certainty.certainty > bestCertainty.certainty) {
                    selected = true;
                    bestCertainty = certainty;
                    selectedAttribute = selectedAtts.get(i);
                }
            }
            if (!selected || bestCertainty.certainty == 0) return;
        }
        else
        if (criteria == Criteria.Entropy)
        {
            if (node.data == null || node.data.size() <= 1) return;
            if (stopConditionAllClassesEqualEfficient(node.frequencyClasses)) return;
                    /*  In the following two loops, the best attribute is located which
                        causes maximum increase in information*/
            Certainty entropy;
            for (int i=0; i< selectedAtts.size(); i++) {
                if ( classAttribute == selectedAtts.get(i) ) continue;
                if (attributeTypes[selectedAtts.get(i)] == AttributeType.Discrete && alreadyUsedToDecompose(node, selectedAtts.get(i))) continue;
                entropy =  calculateEntropy(node.data,selectedAtts.get(i));
                if (entropy.certainty == -1) continue;
                if (!selected) {
                    selected = true;
                    bestCertainty = entropy;
                    selectedAttribute = selectedAtts.get(i);
                } else {
                    if (entropy.certainty < bestCertainty.certainty) {
                        selected = true;
                        bestCertainty = entropy;
                        selectedAttribute = selectedAtts.get(i);
                    }
                }
            }
            if (!selected) return;
        }
        else
        if(criteria == Criteria.Gini){
            if (node.data == null || node.data.size() <= 1) return;
            if (stopConditionAllClassesEqualEfficient(node.frequencyClasses)) return;
                        /*  In the following two loops, the best attribute is located which
                            causes maximum increase in information*/
            Certainty gini;
            for (int i=0; i< selectedAtts.size(); i++) {
                if ( classAttribute == selectedAtts.get(i) ) continue;
                if (attributeTypes[selectedAtts.get(i)] == AttributeType.Discrete && alreadyUsedToDecompose(node, selectedAtts.get(i))) continue;
                gini =  calculateGini(node.data,selectedAtts.get(i));
                if (gini.certainty == -1) continue;
                if (!selected) {
                    selected = true;
                    bestCertainty = gini;
                    selectedAttribute = selectedAtts.get(i);
                } else {
                    if (gini.certainty < bestCertainty.certainty) {
                        selected = true;
                        bestCertainty = gini;
                        selectedAttribute = selectedAtts.get(i);
                    }
                }
            }
            if (!selected) return;
        }

        node.certaintyUsedToDecompose = bestCertainty.certainty;

        //ArrayList<Thread> threads = new ArrayList<Thread>();
        //if attribute is discrete
        if (attributeTypes[selectedAttribute] == AttributeType.Discrete){
            // Now divide the dataset using the selected attribute
            int numvalues = domainsIndexToValue[selectedAttribute].size();
            node.decompositionAttribute = selectedAttribute;
            node.children = new ArrayList<>();
            DataFrequencies df;
            for (int j=0; j< numvalues; j++) {
                if (domainsIndexToValue[selectedAttribute].get(j) == null || domainsIndexToValue[selectedAttribute].get(j).equals("?"))
                    continue;
                TreeNode newNode  = new TreeNode();
                newNode.parent = node;
                //node.children[j].informationUsedToDecompose = bestInformation.information;
                df = getSubset(node.data, selectedAttribute, j);
                newNode.data = df.data;
                newNode.frequencyClasses = df.frequencyClasses;
                newNode.decompositionValue = j;
                node.children.add(newNode);

            }
            // Recursively divides children nodes
            if (isCrossValidation){ // If is Cross Validation, don't create more threads
                for (int j = 0; j < node.children.size(); j++) {
                    decomposeNode(node.children.get(j), selectedAtts, 0);
                }
            }
            else
            if(isRandomForest) { //if is Random Forest, don't create more threads
                Random rand = new Random(mySeed);
                int randomAttribute;
                //randomAttribute = rand.nextInt(numAttributes - 1);
                int numAtt = selectedAtts.size();

                for (int j = 0; j < node.children.size(); j++) {
                    selectedAtts = new ArrayList<>();
                    while (selectedAtts.size() < numAtt) {
                        randomAttribute = rand.nextInt(numAttributes - 1);
                        if (!selectedAtts.contains(randomAttribute) && attributeTypes[randomAttribute] != AttributeType.Ignore) selectedAtts.add(randomAttribute);
                    }
                    decomposeNode(node.children.get(j), selectedAtts, mySeed + 1 + j);
                }
            }
            else{
                for (int j = 0; j < node.children.size(); j++) {//For single trees now also don't create more threads
                    //final Integer j2 = j;
                    //final ArrayList<Integer> selectedAtts2 =  selectedAtts;

                    //if (globalThreads.size() < maxThreads) {
                    //    Thread thread = new Thread() {
                    //        public void run() {
                    decomposeNode(node.children.get(j), selectedAtts, mySeed + 1 + j);
                    //       }
                    //  };

                    //    threads.add(thread);
                    //   globalThreads.add(thread);
                    //   thread.start();
                    //}
                    // else decomposeNode((TreeNode) node.children.get(j2), selectedAtts2, mySeed + 1 + j2);
                }

                //while (threads.size() > 0){
                //    Thread current = threads.get(threads.size()-1);
                //    if (!current.isAlive()) {
                //       globalThreads.remove(current);
                //       threads.remove(current);
                //   }
                //}
            }
        } else { //If attribute is continuous
            node.decompositionAttribute = selectedAttribute;
            node.children = new ArrayList<>();
            DataFrequencies df;
            //First less than threshold
            TreeNode newNode;
            newNode = new TreeNode();
            newNode.parent = node;
            Tuple<DataFrequencies,DataFrequencies> subsets = getSubsetsBelowAndAbove(node.data, selectedAttribute,bestCertainty.threshold);
            df = subsets.x;
            newNode.data = df.data;
            newNode.frequencyClasses = df.frequencyClasses;
            newNode.decompositionValueContinuous = " <= " + bestCertainty.threshold ;
            newNode.decompositionValueContinuousSymbol = "<=";
            newNode.thresholdContinuous = bestCertainty.threshold;
            node.children.add(newNode);

            //Then over the threshold.
            newNode  = new TreeNode();
            newNode.parent = node;
            df = subsets.y;
            newNode.data = df.data;
            newNode.frequencyClasses = df.frequencyClasses;
            newNode.decompositionValueContinuous = " > " + bestCertainty.threshold ;
            newNode.decompositionValueContinuousSymbol = ">";
            newNode.thresholdContinuous = bestCertainty.threshold;
            node.children.add(newNode);

            //Decompose children
            if ((node.children.get(0)).data.isEmpty() || (node.children.get(1)).data.isEmpty())
                return;

            if (isCrossValidation){ //if is a Cross Validation, don't create more threads
                decomposeNode(node.children.get(0), selectedAtts, 0);
                decomposeNode(node.children.get(1), selectedAtts, 0);
            }
            else //if is a Random Forest, don't create more threads
                if(isRandomForest) {
                    Random rand = new Random(mySeed);
                    int randomAttribute;
                    //randomAttribute = rand.nextInt(numAttributes - 1);
                    int numAtt = selectedAtts.size();

                    selectedAtts = new ArrayList<>();
                    while (selectedAtts.size() < numAtt) {
                        randomAttribute = rand.nextInt(numAttributes - 1);
                        if (!selectedAtts.contains(randomAttribute) && attributeTypes[randomAttribute] != AttributeType.Ignore) selectedAtts.add(randomAttribute);
                    }
                    decomposeNode(node.children.get(0), selectedAtts, mySeed + 1);

                    selectedAtts = new ArrayList<>();
                    while (selectedAtts.size() < numAtt) {
                        randomAttribute = rand.nextInt(numAttributes - 1);
                        if (!selectedAtts.contains(randomAttribute) && attributeTypes[randomAttribute] != AttributeType.Ignore) selectedAtts.add(randomAttribute);
                    }
                    decomposeNode(node.children.get(1), selectedAtts, mySeed + 2);
                }
                else {//also now for single trees don't create more threads
                    //final ArrayList<Integer> selectedAtts2 =  selectedAtts;

                    //if (globalThreads.size() < maxThreads) {
                    //   Thread thread = new Thread() {
                    //       public void run() {
                    decomposeNode(node.children.get(0), selectedAtts, mySeed + 1);
                    //       }
                    //   };
                    //   threads.add(thread);
                    //   globalThreads.add(thread);
                    //  thread.start();

                    //  thread = new Thread() {
                    //      public void run() {
                    decomposeNode(node.children.get(1), selectedAtts, mySeed + 2);
                    //      }
                    //  };
                    //  threads.add(thread);
                    //  globalThreads.add(thread);
                    //  thread.start();

                    //  while (threads.size() > 0){
                    //      Thread current = threads.get(threads.size()-1);
                    //      if (!current.isAlive()) {
                    //          globalThreads.remove(current);
                    //          threads.remove(current);
                    //     }
                    //  }
                    //}
                    //else {
                    //    decomposeNode((TreeNode) node.children.get(0), selectedAtts2, mySeed + 1);
                    //    decomposeNode((TreeNode) node.children.get(1), selectedAtts2, mySeed + 2);
                    //}
                }
        }
    }

    public void imputeMissing(){
        for(int attribute = 0; attribute < numAttributes - 1; attribute++){
            if (attributeTypes[attribute] == AttributeType.Continuous){
                if(domainsIndexToValue[attribute].containsValue("?")){
                    //Find mean value
                    double mean = meanValues[attribute];
                    //Get index
                    int index = domainsValueToIndex[attribute].get("?");
                    //Replace missing with mean
                    domainsIndexToValue[attribute].replace(index,"?",mean);
                    domainsValueToIndex[attribute].remove("?");
                    domainsValueToIndex[attribute].put(mean,index);
                }
            }
            else
            if (attributeTypes[attribute] == AttributeType.Discrete){
                if(domainsIndexToValue[attribute].containsValue("?")){
                    //Find most common value
                    int mostCommonValue = mostCommonValues[attribute];
                    String mostCommonValueStr = (String) domainsIndexToValue[attribute].get(mostCommonValue);
                    //Get index
                    int index = domainsValueToIndex[attribute].get("?");
                    //Replace missing with most common
                    domainsIndexToValue[attribute].replace(index,"?",mostCommonValueStr);
                    domainsValueToIndex[attribute].remove("?");
                    domainsValueToIndex[attribute].put(mostCommonValueStr,index);
                }
            }
        }
    }
    //Find the mean value of a continuous attribute
    public double meanValue(int attribute){
        double sum = 0;
        int counter = 0;

        for (int i = 0; i < trainData.size(); i++){
            DataPoint point = trainData.get(i);
            try {
                double attValue = (double) domainsIndexToValue[attribute].get(point.attributes[attribute]);
                sum += attValue;
                counter++;
            }
            catch (Exception e){
                //continue;
            }
        }
        return sum/counter;
    }

    public void setMeanValues(){
        meanValues = new double[numAttributes - 1];

        for (int i = 0; i < numAttributes -1; i++){
            if (attributeTypes[i] == AttributeType.Ignore) continue;
            if (attributeTypes[i] == AttributeType.Continuous) {
                meanValues[i] = meanValue(i);
            }
            else meanValues[i] = 0;
        }
    }

    //Find the most common values of a discrete attribute. This is neede for imputation.
    public int mostCommonValue(int attribute){
        int[] frequencies = new int[domainsIndexToValue[attribute].size()];
        for (int i = 0; i < trainData.size(); i++){
            DataPoint point = trainData.get(i);
            frequencies[point.attributes[attribute]]++;
        }
        int mostFrequent = 0;
        int index = 0;
        for (int i = 0; i < frequencies.length; i++){
            if (!(domainsIndexToValue[attribute].get(i)).equals("?"))
                if (frequencies[i] > mostFrequent) {
                    mostFrequent = frequencies[i];
                    index = i;
                }
        }
        return index;
    }

    public void setMostCommonValues(){
        mostCommonValues = new int[numAttributes - 1];

        for (int i = 0; i < numAttributes -1; i++) {
            if (attributeTypes[i] == AttributeType.Ignore) continue;
            if (attributeTypes[i] == AttributeType.Discrete) {
                mostCommonValues[i] = mostCommonValue(i);
            }
            else mostCommonValues[i] = 0;
        }
    }

    public void readTestData(String filename){
        //Read the test file
        FileInputStream in = null;
        ArrayList<DataPoint> data = new ArrayList<>();
        try {
            File inputFile = new File(filename);
            in = new FileInputStream(inputFile);
        } catch ( Exception e) {
            System.err.println( "Unable to open data file: " + filename + "\n" + e);
            System.exit(1);
        }

        BufferedReader bin = new BufferedReader(new InputStreamReader(in) );
        String input = null;

        while(true) {
            try {
                input = bin.readLine();
            }
            catch (Exception e){
                System.err.println( "Unable to read line from test file.");
                System.exit(1);
            }
            if (input == null) {
                System.err.println( "No data found in the data file: " + filename + "\n");
                System.exit(1);
            }
            if (input.startsWith("//")) continue;
            if (input.equals("")) continue;
            break;
        }

        StringTokenizer tokenizer;
        while(input != null) {
            //if (input == null) break;
            tokenizer = new StringTokenizer(input,",");
            DataPoint point = new DataPoint(numAttributes);
            String next;
            for (int i=0; i < numAttributes; i++) {
                next = tokenizer.nextToken().trim();
                if(attributeTypes[i] == AttributeType.Continuous) {
                    if (next.equals("?") || next.equals("NaN"))
                        point.attributes[i] = getSymbolValue(i, "?");
                    else {
                        try {
                            point.attributes[i] = getSymbolValue(i, Double.parseDouble(next));
                        }
                        catch (Exception e){
                            System.err.println( "Error reading continuous value in test data.");
                            System.exit(1);
                        }
                    }
                }
                else
                if (attributeTypes[i] == AttributeType.Discrete) {
                    point.attributes[i] = getSymbolValue(i, next);
                }
                else
                if (attributeTypes[i] == AttributeType.Ignore){
                    point.attributes[i]  = getSymbolValue(i, next);
                }
            }
            data.add(point);
            //root.data.add(point);
            try {
                input = bin.readLine();
            }
            catch (Exception e){
                System.err.println( "Unable to read line from test file.");
                System.exit(1);
            }
        }

        try {
            bin.close();
        }
        catch (Exception e){
            System.err.println( "Unable to close test file.");
            System.exit(1);
        }
        testData = data;

        //Resize root.frequencyClasses in case new class values were found in test dataset
        if(root.frequencyClasses.length < domainsIndexToValue[classAttribute].size()){
            int[] newArray = new int[domainsIndexToValue[classAttribute].size()];
            java.lang.System.arraycopy(root.frequencyClasses, 0, newArray, 0, root.frequencyClasses.length);
            root.frequencyClasses = newArray;
        }

        System.out.print("Read data: " + testData.size() + " cases for testing. ");
        System.out.print("\n");
    }

    /** Function to read the data file.
     The first line of the data file should contain the names of all attributes.
     The number of attributes is inferred from the number of words in this line.
     The last word is taken as the name of the output attribute.
     Each subsequent line contains the values of attributes for a data point.
     If any line starts with // it is taken as a comment and ignored.
     Blank lines are also ignored.
     */
    public void readData(String filename) {

        FileInputStream in = null;
        ArrayList<DataPoint> data = new ArrayList<>();
        int numTraining;

        try {
            File inputFile = new File(filename);
            in = new FileInputStream(inputFile);
        } catch ( Exception e) {
            System.err.println( "Unable to open data file: " + filename + "\n");
            System.exit(1);
        }

        BufferedReader bin = new BufferedReader(new InputStreamReader(in) );
        String input = null;
        StringTokenizer tokenizer;

        //Read names file
        readNames(filename);
        try {
            input = bin.readLine();
        }
        catch (Exception e){
            System.err.println( "Unable to read line from data file.");
            System.exit(1);
        }
        while(input != null) {
            if (input.trim().equals("")) break;
            if (input.startsWith("//")) continue;
            if (input.equals("")) continue;

            tokenizer = new StringTokenizer(input, ",");
            int numtokens = tokenizer.countTokens();
            if (numtokens != numAttributes) {
                System.err.println( "Read " + data.size() + " data");
                System.err.println( "Last line read: " + input);
                System.err.println( "Expecting " + numAttributes  + " attributes");
                System.exit(1);
            }

            //Insert missing value "?" into discrete attributes. This is needed for later accepting missing values.
            /*for (int i = 0; i < numAttributes - 1; i++) {
                if (attributeTypes[i] == AttributeType.Discrete){
                    getSymbolValue(i, "?");
                }
            }*/

            DataPoint point = new DataPoint(numAttributes);
            String next;
            for (int i=0; i < numAttributes; i++) {
                next = tokenizer.nextToken().trim();
                if(attributeTypes[i] == AttributeType.Continuous) {
                    if (next.equals("?") || next.equals("NaN"))
                        point.attributes[i] = getSymbolValue(i, "?");
                    else
                    {
                        try {
                            point.attributes[i] = getSymbolValue(i, Double.parseDouble(next));
                        }
                        catch (Exception e){
                            System.err.println( "Error reading continuous value in train data.");
                            System.exit(1);
                        }
                    }
                }
                else
                if (attributeTypes[i] == AttributeType.Discrete) {
                    point.attributes[i] = getSymbolValue(i, next);
                }
                else
                if (attributeTypes[i] == AttributeType.Ignore){
                    point.attributes[i]  = getSymbolValue(i, next);
                }
            }
            data.add(point);
            try {
                input = bin.readLine();
            }
            catch (Exception e){
                System.err.println( "Unable to read line from data file.");
                System.exit(1);
            }
        }
        try {
            bin.close();
        }
        catch (Exception e){
            System.err.println( "Unable to close data file.");
            System.exit(1);
        }


        int size = data.size();

        root.frequencyClasses = new int[domainsIndexToValue[classAttribute].size()];
        if (splitTrainData && !testDataExists && !isCrossValidation){
            //Randomize the data
            Collections.shuffle(data);
            numTraining = size * 80/100;
            for (int i = 0; i < size; i++){
                if (i < numTraining){
                    DataPoint point = data.get(i);
                    root.data.add(point);
                    root.frequencyClasses[point.attributes[classAttribute]]++;
                }
                else testData.add(data.get(i));
            }
        }
        //here I need to loop through the data and add one by one while updating FrequencyClasses
        else {
            for (int i = 0; i < size; i++){
                DataPoint point = data.get(i);
                root.data.add(point);
                root.frequencyClasses[point.attributes[classAttribute]]++;
            }
        }

        trainData = root.data;
        if (splitTrainData && !testDataExists){
            System.out.print("Read data: " + root.data.size() + " cases for training.");
            System.out.print("\n");
            System.out.print("Read data: " + testData.size() + " cases for testing.");
        }
        else System.out.print("Read data: " + root.data.size() + " cases for training. ");
        System.out.print("\n");
    }	// End of function readData

    public void readNames(String filename){
        FileInputStream in = null;

        String input = null;
        ArrayList<Tuple<String, String>> attributes = new ArrayList<>();
        //Read the names file
        try {
            if (filename.contains(".")){
                String[] split = filename.split("\\.");
                File inputFile = new File(split[0] + ".names");
                in = new FileInputStream(inputFile);
            }
            else {
                File inputFile = new File(fileName + ".names");
                in = new FileInputStream(inputFile);
            }


        } catch ( Exception e) {
            System.err.println( "Unable to open names file.");
            System.exit(1);
        }
        BufferedReader bin = new BufferedReader(new InputStreamReader(in) );

        //Read first line containing class values.
        try {
            input = bin.readLine();
        }
        catch (Exception e){
            System.err.println( "Unable to read line in names file.");
            System.exit(1);
        }

        //Save attribute names and types to a tuple array.
        try {
            input = bin.readLine();
        }
        catch (Exception e){
            System.err.println( "Unable to read line in names file.");
            System.exit(1);
        }
        while(input != null) {
            if (!input.startsWith("|")) {
                String[] split = input.split(":");
                if (split.length == 2) {
                    Tuple<String, String> t = new Tuple<>(split[0].trim(), split[1].trim());
                    attributes.add(t);
                }
            }
            try {
                input = bin.readLine();
            }
            catch (Exception e){
                System.err.println( "Unable to read line in names file.");
                System.exit(1);
            }
        }

        //Set numAttributes. +1 for the class attribute
        numAttributes = attributes.size() + 1;

        //Set class attribute
        classAttribute = numAttributes -1;

        //Check for errors.
        if (numAttributes <= 1) {
            System.err.println( "Read line: " + input);
            System.err.println( "Could not obtain the names of attributes in the line");
            System.err.println( "Expecting at least one input attribute and one output attribute");
            System.exit(1);
        }

        //Initialize domains
        domainsIndexToValue = new HashMap [numAttributes];
        domainsValueToIndex = new HashMap [numAttributes];
        //domains = new ArrayList[numAttributes];
        for (int i=0; i < numAttributes; i++) domainsIndexToValue[i] = new HashMap<>();
        for (int i=0; i < numAttributes; i++) domainsValueToIndex[i] = new HashMap<>();

        //Set attributeNames. They should be in the same order as they appear in the data. +1 for the class
        attributeNames = new String[numAttributes];
        for (int i=0; i < numAttributes - 1; i++) {
            Tuple<String,String> t = attributes.get(i);
            attributeNames[i]  = t.x;
        }

        //Set the class. For now all class attribute names are the same: Class.
        attributeNames[numAttributes - 1] = "Class";

        //Initialize attributeTypes.
        attributeTypes = new AttributeType[numAttributes];

        //Set the attribute types.
        for (int i=0; i< numAttributes - 1; i++){
            Tuple<String,String> attribute = attributes.get(i);
            switch (attribute.y.trim()){
                case "continuous.": attributeTypes[i] = AttributeType.Continuous;
                    break;
                case "ignore.": attributeTypes[i] = AttributeType.Ignore;
                    break;
                default: attributeTypes[i] = AttributeType.Discrete;
                    break;
            }
        }

        //Set attribute type for the class.
        attributeTypes[numAttributes - 1] = AttributeType.Discrete;
    }

    //-----------------------------------------------------------------------

    /*  This function prints the decision tree in the form of rules.
        The action part of the rule is of the form
            outputAttribute = "symbolicValue"
        or
            outputAttribute = { "Value1", "Value2", ..  }
        The second form is printed if the node cannot be decomposed any further into an homogenous set
    */
    public void printTree(TreeNode node, String tab) {

        int outputattr = classAttribute;
        if (node.data != null && !node.data.isEmpty()) totalNodes++;
        if (node.children == null) {
            if (node.data != null && !node.data.isEmpty()) totalRules++;
            //int []values = getAllValues(node.data, outputattr );
            //if (values.length == 1) {
//				System.out.println(tab + "\t" + attributeNames[outputattr] + " = \"" + domainsIndexToValue[outputattr].get(values[0]) + "\";");
            //   return;
            //}
//			System.out.print(tab + "\t" + attributeNames[outputattr] + " = {");
//			for (int i=0; i < values.length; i++) {
//				System.out.print("\"" + domainsIndexToValue[outputattr].get(values[i]) + "\" ");
//				if ( i != values.length-1 ) System.out.print( " , " );
//			}
//			System.out.println( " };");
            return;
        }

        int numvalues = node.children.size();
        for (int i=0; i < numvalues; i++) {
            String symbol;
//                  if (attributeTypes[node.decompositionAttribute] == AttributeType.Continuous)
//                      symbol = node.children[i].decompositionValueContinuous;
//                  else symbol = " == " + domainsIndexToValue[node.decompositionAttribute].get(i);
//                  System.out.println(tab + "if( " + attributeNames[node.decompositionAttribute] + symbol + "" +
//                          ") {" );
            printTree(node.children.get(i), tab + "\t");
//                  if (i != numvalues-1) System.out.print(tab +  "} else ");
//                  else System.out.println(tab +  "}");
        }
    }

    private void createFile(){
        ObjectOutputStream oos;
        FileOutputStream fout;
        String fName = fileName;
        fName = fName.substring(0, fName.length() - 4);
        fName = fName + "tree";
        try {
            //Check if file exists...delete it
            File inputFile = new File(fName);
            if (inputFile.exists()) inputFile.delete();

            //Serialize and save to disk
            fout = new FileOutputStream(fName, false);
            GZIPOutputStream gz = new GZIPOutputStream(fout);
            oos = new ObjectOutputStream(gz);
            oos.writeObject(this);
            oos.close();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void createFileRF(){
        ObjectOutputStream oos;
        FileOutputStream fout;
        String fName = fileName;
        fName = fName.substring(0, fName.length() - 4);
        fName = fName + "forest";
        try{
            //Check if file exists...delete it
            File inputFile = new File(fName);
            if (inputFile.exists()) inputFile.delete();

            //Serialize and save to disk
            fout = new FileOutputStream(fName, false);
            GZIPOutputStream gz = new GZIPOutputStream(fout);
            oos = new ObjectOutputStream(gz);
            oos.writeObject(this);
            oos.close();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private cid3 deserializeFile(String file){
        cid3 ret = null;
        ObjectInputStream objectinputstream;
        try {
            //FileInputStream streamIn = new FileInputStream(file);
            GZIPInputStream is = new GZIPInputStream(new FileInputStream(file));
            objectinputstream = new ObjectInputStream(is);
            ret = (cid3) objectinputstream.readObject();
            objectinputstream.close();
        }
        catch (Exception e) {
            System.out.print("Error deserializing file.");
            System.out.print("\n");
            System.exit(1);
        }
        return ret;
    }

    public static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long absSeconds = Math.abs(seconds);
        String positive = String.format(
                "%d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        return seconds < 0 ? "-" + positive : positive;
    }

    public void createDecisionTree() {
        Instant start = Instant.now();

        ArrayList<Integer> selectedAttributes = new ArrayList<>();
        //Select ALL attributes
        for (int i=0; i < numAttributes; i++){
            if (attributeTypes[i] == AttributeType.Ignore) continue;
            selectedAttributes.add(i);
        }
        decomposeNode(root,selectedAttributes, 0);
        System.out.print("Decision tree created.");
        System.out.print("\n");
        printTree(root, "");
        System.out.print("Rules:" + totalRules);
        System.out.print("\n");
        System.out.print("Nodes:" + totalNodes);
        System.out.print("\n");
        testDecisionTree();
        System.out.print("\n");

        Instant finish = Instant.now();
        Duration timeElapsed = Duration.between(start, finish);
        String timeElapsedString = formatDuration(timeElapsed);
        System.out.print("Time: " + timeElapsedString);
        System.out.print("\n");
    }

    public void createCrossValidation() {
        Instant start = Instant.now();

        if (testDataExists){
            trainData.addAll(testData);
            root.data = trainData;
        }

        int chunk_size = root.data.size()/10;
        int modulus = root.data.size()%10;
        int counter = chunk_size;
        int counter_chunks = 0;
        //Randomize the data
        Collections.shuffle(root.data);

        //Initialize chunks
        for (int i = 0; i < 10; i++){
            crossValidationChunks[i] = new ArrayList<>();
        }

        //First check if there is a remainder
        if (modulus != 0){
            for (int i = 0; i < root.data.size() - modulus; i++){
                if (i < counter) {
                    crossValidationChunks[counter_chunks].add(root.data.get(i));
                }
                else {
                    counter+= chunk_size;
                    counter_chunks++;
                    i--;
                }
            }
            counter = 0;
            for (int i = root.data.size() - modulus; i < root.data.size(); i++){
                crossValidationChunks[counter].add(root.data.get(i));
                counter++;
            }
        }
        else {
            for (int i = 0; i < root.data.size(); i++){
                if (i < counter) {
                    crossValidationChunks[counter_chunks].add(root.data.get(i));
                }
                else {
                    counter+= chunk_size;
                    counter_chunks++;
                    i--;
                }
            }
        }

        ArrayList<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            TreeNode newRoot = new TreeNode();
            ArrayList<DataPoint> trainData = new ArrayList<>();
            for (int k = 0; k < 10; k++){
                if (k != i) trainData.addAll(crossValidationChunks[k]);
            }
            newRoot.data = trainData;
            newRoot.frequencyClasses =  getFrequencies(newRoot.data);
            rootsCrossValidation.add(newRoot);

            //Create the cross-validation in parallel
            ArrayList<Integer> selectedAttributes = new ArrayList<>();
            //Select ALL attributes
            for (int j=0; j < numAttributes - 1; j++){
                if (attributeTypes[j] == AttributeType.Ignore) continue;
                selectedAttributes.add(j);
            }
            final Integer i2 = i;
            Thread thread = new Thread(){
                public void run(){
                    decomposeNode(rootsCrossValidation.get(i2), selectedAttributes, 0);
                }
            };
            threads.add(thread);
            thread.start();
        }

        while (threads.size() > 0){
            if (!threads.get(threads.size()-1).isAlive())
                threads.remove(threads.size()-1);
        }

        System.out.print("\n");
        System.out.print("10-fold cross-validation created with " + root.data.size() + " cases.");
        System.out.print("\n");

        testCrossValidation();
        Instant finish = Instant.now();
        Duration timeElapsed = Duration.between(start, finish);
        String timeElapsedString = formatDuration(timeElapsed);
        System.out.print("\n");
        System.out.print("Time: " + timeElapsedString);
        System.out.print("\n");
    }

    public void createCrossValidationRF() {
        Instant start = Instant.now();

        //Initialize array
        for (int i = 0; i < 10; i++){
            cvRandomForests[i] = new ArrayList<>();
        }

        if (testDataExists){
            trainData.addAll(testData);
            root.data = trainData;
        }

        int chunk_size = root.data.size()/10;
        int modulus = root.data.size()%10;
        int counter = chunk_size;
        int counter_chunks = 0;
        //Randomize the data
        Collections.shuffle(root.data);

        //Initialize chunks
        for (int i = 0; i < 10; i++){
            crossValidationChunks[i] = new ArrayList<>();
        }
        //First check if there is a remainder
        if (modulus != 0){
            for (int i = 0; i < root.data.size() - modulus; i++){
                if (i < counter) {
                    crossValidationChunks[counter_chunks].add(root.data.get(i));
                }
                else {
                    counter+= chunk_size;
                    counter_chunks++;
                    i--;
                }
            }
            counter = 0;
            for (int i = root.data.size() - modulus; i < root.data.size(); i++){
                crossValidationChunks[counter].add(root.data.get(i));
                counter++;
            }
        }
        else {
            for (int i = 0; i < root.data.size(); i++){
                if (i < counter) {
                    crossValidationChunks[counter_chunks].add(root.data.get(i));
                }
                else {
                    counter+= chunk_size;
                    counter_chunks++;
                    i--;
                }
            }
        }

        //Create the 10 Random Forests
        for (int i = 0; i < 10; i++) {
            ArrayList<DataPoint> trainData = new ArrayList<>();
            for (int k = 0; k < 10; k++){
                if (k != i) trainData.addAll(crossValidationChunks[k]);
            }

            createRandomForest(trainData, cvRandomForests[i],true);
        }

        System.out.print("\n");
        System.out.print("10-fold Random Forests cross-validation created with " + root.data.size() + " cases.");
        System.out.print("\n");

        //Test the cross-validation
        testCrossValidationRF();

        Instant finish = Instant.now();
        Duration timeElapsed = Duration.between(start, finish);
        String timeElapsedString = formatDuration(timeElapsed);
        System.out.print("\n");
        System.out.print("Time: " + timeElapsedString);
        System.out.print("\n");
    }

    public void createRandomForest(ArrayList<DataPoint> data, ArrayList<TreeNode> roots, boolean cv) {
        Instant start = Instant.now();
        int numberOfAttributes = 0;
        for (int i = 0; i < numAttributes - 1; i++){
            if (attributeTypes[i] != AttributeType.Ignore) numberOfAttributes++;
        }
        double numAttributesForRandomForest = Math.log(numberOfAttributes + 1) / Math.log(2);

        int numAttributesForRandomForestInt = (int) numAttributesForRandomForest;
        int randomAttribute;
        ArrayList<Integer> selectedAttributes;
        ArrayList<Thread> threads = new ArrayList<>();
        Random rand = new Random(seed);

        for (int i = 0; i < numberOfTrees; i++) {
            selectedAttributes = new ArrayList<>();
            while (selectedAttributes.size() < numAttributesForRandomForestInt){
                randomAttribute = rand.nextInt(numAttributes - 1);
                if (!selectedAttributes.contains(randomAttribute) && attributeTypes[randomAttribute] != AttributeType.Ignore) selectedAttributes.add(randomAttribute);
            }

            TreeNode cloneRoot = new TreeNode();
            cloneRoot.data = data;
            cloneRoot.frequencyClasses =  getFrequencies(data);
            roots.add(cloneRoot);

            //Create the Random Forest in parallel
            final Integer i2 = i;
            final ArrayList<Integer> selectedAttributes2 =  selectedAttributes;
            Thread thread = new Thread(){
                public void run(){
                    decomposeNode(roots.get(i2), selectedAttributes2, seed + 1 + i2);
                    //System.out.print("Tree " + Integer.toString(i2 + 1) + " created.");
                    //System.out.print("\n");
                }
            };
            threads.add(thread);
            thread.start();
        }

        while (threads.size() > 0){
            if (!threads.get(threads.size()-1).isAlive())
                threads.remove(threads.size()-1);
        }

        if (!cv) {
            System.out.print("\n");
            System.out.print("Random Forest of " + roots.size() + " trees created.");
            System.out.print("\n");
            System.out.print("\n");
            testRandomForest();
            Instant finish = Instant.now();
            Duration timeElapsed = Duration.between(start, finish);
            String timeElapsedString = formatDuration(timeElapsed);
            System.out.print("\n");
            System.out.print("\n");
            System.out.print("Time: " + timeElapsedString);
            System.out.print("\n");
        }
    }

    public TreeNode testExamplePoint(DataPoint example, TreeNode node){
        int splitAttribute, attributeValue;
        double attributeRealValue;
        splitAttribute = node.decompositionAttribute;
        attributeValue = example.attributes[splitAttribute];

        if(node.children == null || node.children.isEmpty()) return node;

        //Check if attribute is discrete
        if (attributeTypes[splitAttribute] == AttributeType.Discrete) {
            for (int i = 0; i < node.children.size(); i++) {
                if ((node.children.get(i)).decompositionValue == attributeValue) {
                    node = node.children.get(i);
                    break;
                }
            }
        }
        //Check if attribute is continuous
        if (attributeTypes[splitAttribute] == AttributeType.Continuous){
            attributeRealValue = (double)domainsIndexToValue[splitAttribute].get(example.attributes[splitAttribute]);
            if (attributeRealValue <= (node.children.get(0)).thresholdContinuous){
                node = node.children.get(0);
            }
            else node = node.children.get(1);
        }
        return testExamplePoint(example,node);
    }

    public boolean testExample(DataPoint example){
        TreeNode node;
        node = testExamplePoint(example, root);
        if (node.data == null || node.data.isEmpty()){
            if (example.attributes[classAttribute] == mostCommonFinal(node.parent)) return true;
        }
        else{
            if (example.attributes[classAttribute] == mostCommonFinal(node)) return true;
        }
        return false;
    }

    public boolean testExampleCV(DataPoint example, TreeNode tree){
        TreeNode node;
        node = testExamplePoint(example, tree);
        if (node.data == null || node.data.isEmpty()){
            if (example.attributes[classAttribute] == mostCommonFinal(node.parent)) return true;
        }
        else{
            if (example.attributes[classAttribute] == mostCommonFinal(node)) return true;
        }
        return false;
    }

    public boolean testExampleRF(DataPoint example, ArrayList<TreeNode> roots){
        TreeNode node;
        int tru = 0;
        int fals = 0;

        ArrayList<Boolean> results = new ArrayList<>();
        for (int i=0; i < roots.size(); i++){
            node = testExamplePoint(example, roots.get(i));
            if (node.data == null || node.data.isEmpty()){
                if (example.attributes[classAttribute] == mostCommonFinal(node.parent)) results.add(true);
                else results.add(false);
            }
            else{
                if (example.attributes[classAttribute] == mostCommonFinal(node)) results.add(true);
                else results.add(false);
            }
        }
        //Voting now
        for (int i=0; i < results.size(); i++){
            if (results.get(i)) tru++;
            else fals++;
        }
        return tru > fals;
    }

    public void testDecisionTree(){
        int test_errors = 0;
        int test_corrects = 0;
        int test_size = testData.size();
        for (int i = 0; i < test_size; i++) {
            DataPoint point = testData.get(i);
            if (testExample(point)) test_corrects++;
            else test_errors++;
        }

        int train_errors = 0;
        int train_corrects = 0;
        int train_size = trainData.size();
        for (int i = 0; i < train_size; i++){
            DataPoint point = trainData.get(i);
            if (testExample(point)) train_corrects++;
            else train_errors++;
        }
        System.out.print("\n");
        System.out.print("TRAIN DATA: ");
        System.out.print("\n");
        System.out.print("=================================");
        System.out.print("\n");
        System.out.print("Correct guesses: " + train_corrects);
        System.out.print("\n");
        double rounded = Math.round((1.*train_errors*100/trainData.size()) * 10) / 10.0;
        System.out.print("Incorrect guesses: " + train_errors + " (" + rounded + "%)");
        System.out.print("\n");

        if (!this.testData.isEmpty()) {
            System.out.print("\n");
            System.out.print("TEST DATA: ");
            System.out.print("\n");
            System.out.print("=================================");
            System.out.print("\n");
            System.out.print("Correct guesses: " + test_corrects);
            System.out.print("\n");
            double rounded1 = Math.round((1. * test_errors * 100 / testData.size()) * 10) / 10.0;
            System.out.print("Incorrect guesses: " + test_errors + " (" + rounded1 + "%)");
        }
        System.out.print("\n");
        System.out.print("\n");
        System.out.print("Root: " + attributeNames[root.decompositionAttribute]);
        System.out.print("\n");
    }

    public void testCrossValidation(){
        int test_errors = 0;
        int test_corrects = 0;
        double meanErrors = 0;
        double percentageErrors = 0;
        double[] errorsFoldK = new double[10];
        for (int i = 0; i < 10; i ++){
            test_errors = 0;
            TreeNode currentTree = rootsCrossValidation.get(i);
            ArrayList<DataPoint> currentTest = crossValidationChunks[i];
            for (int j = 0; j < currentTest.size(); j++){
                DataPoint point = (DataPoint)currentTest.get(j);
                if (testExampleCV(point, currentTree)) test_corrects++;
                else test_errors++;
            }
            percentageErrors += (1.*test_errors/currentTest.size())*100;

            double rounded1 = Math.round((1.*test_errors/currentTest.size())*100 * 10) / 10.0;
            System.out.print("\n");
            System.out.print("Fold #" + (i + 1) + " Errors: " + rounded1 + "%");
            //Save k errors for SE
            errorsFoldK[i] = (1.*test_errors/currentTest.size())*100;
        }
        meanErrors = percentageErrors/10;
        double rounded1 = Math.round(meanErrors * 10) / 10.0;
        System.out.print("\n");
        System.out.print("\n");
        System.out.print("Mean errors: " + rounded1 + "%");

        //Calculate average
        double meanFolds = 0;
        for (int i = 0; i < 10 ; i++){
            meanFolds+=errorsFoldK[i];
        }
        meanFolds = 1.*meanFolds/10;

        //Calculate SE (Standard Errors)
        double sum_meanSE = 0;
        for (int i = 0; i < 10; i++){
            sum_meanSE += (1.*errorsFoldK[i] - meanFolds) * (1.*errorsFoldK[i] - meanFolds);
        }
        sum_meanSE = Math.sqrt(sum_meanSE/10);
        double SE = sum_meanSE/Math.sqrt(10);

        double roundedSE = Math.round(SE * 10) / 10.0;
        System.out.print("\n");
        System.out.print("SE: " + roundedSE + "%");
        System.out.print("\n");
    }

    public void testCrossValidationRF(){
        double sum = 0;
        double[] errorsFoldK = new double[10];
        double current = 0;
        //For each Random Forest
        for (int i = 0; i < 10; i ++) {
            ArrayList<TreeNode> currentForest = cvRandomForests[i];
            ArrayList<DataPoint> currentTestData = crossValidationChunks[i];
            current = testRandomForest(currentTestData,currentForest,i+1);
            sum += current;
            //Save k errors for SE
            errorsFoldK[i] = current;
        }

        double meanErrors = sum/10;
        double rounded1 = Math.round(meanErrors * 10) / 10.0;
        System.out.print("\n");
        System.out.print("\n");
        System.out.print("Mean errors: " + rounded1 + "%");
        System.out.print("\n");

        //Calculate SE (Standard Errors)
        double sum_meanSE = 0;
        for (int i = 0; i < 10; i++){
            sum_meanSE += (1.*errorsFoldK[i] - meanErrors) * (1.*errorsFoldK[i] - meanErrors);
        }
        sum_meanSE = Math.sqrt(sum_meanSE/10);
        double SE = sum_meanSE/Math.sqrt(10);

        double roundedSE = Math.round(SE * 10) / 10.0;
        System.out.print("SE: " + roundedSE + "%");
        System.out.print("\n");
    }

    //This overload method is intended to be used when Random Forest cross-validation is selected.
    public double testRandomForest(ArrayList testD, ArrayList<TreeNode> roots, int index){
        int test_errors = 0;
        int test_corrects = 0;
        int test_size = testD.size();
        for (int i = 0; i < test_size; i++){
            DataPoint point = (DataPoint)testD.get(i);
            if (testExampleRF(point, roots)) test_corrects++;
            else test_errors++;
        }
        System.out.print("\n");
        double rounded1 = Math.round((1.*test_errors*100/test_size) * 10) / 10.0;
        System.out.print("Fold #" + index +" Errors: " + rounded1 + "%");
        return rounded1;
    }

    public void testRandomForest(){
        int test_errors = 0;
        int test_corrects = 0;
        int test_size = testData.size();
        for (int i = 0; i < test_size; i++) {
            DataPoint point = testData.get(i);
            if (testExampleRF(point, rootsRandomForest)) test_corrects++;
            else test_errors++;
        }

        int train_errors = 0;
        int train_corrects = 0;
        int train_size = trainData.size();
        for (int i = 0; i < train_size; i++){
            DataPoint point = trainData.get(i);
            if (testExampleRF(point, rootsRandomForest)) train_corrects++;
            else train_errors++;
        }
        System.out.print("TRAIN DATA: ");
        System.out.print("\n");
        System.out.print("=================================");
        System.out.print("\n");
        System.out.print("Correct guesses: " + train_corrects);
        System.out.print("\n");
        double rounded = Math.round((1.*train_errors*100/trainData.size()) * 10) / 10.0;
        System.out.print("Incorrect guesses: " + train_errors + " (" + rounded + "%)");
        System.out.print("\n");

        if (!this.testData.isEmpty()) {
            System.out.print("\n");
            System.out.print("TEST DATA: ");
            System.out.print("\n");
            System.out.print("=================================");
            System.out.print("\n");
            System.out.print("Correct guesses: " + test_corrects);
            System.out.print("\n");
            double rounded1 = Math.round((1. * test_errors * 100 / testData.size()) * 10) / 10.0;
            System.out.print("Incorrect guesses: " + test_errors + " (" + rounded1 + "%)");
        }
    }

    public void queryTree(String file) {
        if (!file.endsWith(".tree")) file += ".tree";
        File inputFile = new File(file);
        cid3 id3;
        if (inputFile.exists()) {
            Scanner in = new Scanner(System.in);
            id3 = deserializeFile(file);
            System.out.print("\n");
            System.out.print("Tree file deserialized.");
            System.out.print("\n");
            TreeNode currentNode = id3.root;
            int attributeValue = 0;
            while (!(currentNode.children == null || currentNode.children.isEmpty())) {
                //If attribute is discrete, show all possible values for it
                if (id3.attributeTypes[currentNode.decompositionAttribute] == AttributeType.Discrete) {
                    System.out.print("Please enter attribute: " + id3.attributeNames[currentNode.decompositionAttribute]);
                    System.out.print("\n");
                    System.out.print("(possible values are: ");
                    String values = "";
                    ArrayList<String> valuesArray = new ArrayList<>();
                    for (int i = 0; i < id3.domainsIndexToValue[currentNode.decompositionAttribute].size(); i++) {
                        valuesArray.add((String) id3.domainsIndexToValue[currentNode.decompositionAttribute].get(i));
                    }
                    Collections.sort(valuesArray);
                    for (int i = 0; i < valuesArray.size(); i++){
                        values += valuesArray.get(i);
                        values += ", ";
                    }
                    values = "?, " + values.substring(0, values.length() - 2);
                    System.out.print(values + ")");
                    System.out.print("\n");

                    while(true) {
                        try {
                            String s = in.nextLine();
                            if (s.equals("?")){
                                attributeValue = id3.mostCommonValues[currentNode.decompositionAttribute];
                                break;
                            }
                            else
                                attributeValue = id3.domainsValueToIndex[currentNode.decompositionAttribute].get(s);
                            break;
                        }
                        catch(Exception e){
                            System.out.println("Please enter a valid value:");
                        }
                    }
                }
                for (int i = 0; i < currentNode.children.size(); i++){
                    //Check if attribute is continuous
                    if(id3.attributeTypes[currentNode.decompositionAttribute] == AttributeType.Continuous){
                        if ((currentNode.children.get(i)).decompositionValueContinuousSymbol.equals("<=")){
                            System.out.print("Is attribute: " + id3.attributeNames[currentNode.decompositionAttribute] + " <= " + (currentNode.children.get(i)).thresholdContinuous + " ? (y/n/?)");
                            System.out.print("\n");
                            String s = in.nextLine();
                            if(s.equals("y") || s.equals("Y")|| s.equals("yes") || s.equals("Yes") || s.equals("YES") || s.equals("n") || s.equals("N") || s.equals("no") || s.equals("No") || s.equals("NO") || s.equals("?"))  {
                                if (s.equals("y") || s.equals("Y") || s.equals("yes") || s.equals("Yes") || s.equals("YES")) {
                                    currentNode = currentNode.children.get(i);
                                    break;
                                }
                                else
                                if (s.equals("?")){
                                    double mean = id3.meanValues[currentNode.decompositionAttribute];
                                    if (mean <= (currentNode.children.get(i)).thresholdContinuous) {
                                        currentNode = currentNode.children.get(i);
                                        break;
                                    }
                                    else {
                                        currentNode = currentNode.children.get(i + 1);
                                        break;
                                    }
                                }
                                else {
                                    currentNode = currentNode.children.get(i + 1);
                                    break;
                                }
                            }
                            else{
                                System.out.print("\n");
                                System.out.print("Error: wrong input value");
                                System.out.print("\n");
                                System.exit(1);
                            }
                        }
                    }
                    else
                    if ((currentNode.children.get(i)).decompositionValue == attributeValue){
                        currentNode = currentNode.children.get(i);
                        break;
                    }
                }
            }
            //Check if the node is empty, if so, return its parent most frequent class.
            boolean isEmpty = true;
            for (int i = 0; i < currentNode.frequencyClasses.length; i ++){
                if (currentNode.frequencyClasses[i] != 0){
                    isEmpty = false;
                    break;
                }
            }
            int mostCommon;
            if (isEmpty) mostCommon = id3.mostCommonFinal(currentNode.parent);
            else mostCommon = id3.mostCommonFinal(currentNode);

            String mostCommonStr = (String) id3.domainsIndexToValue[id3.classAttribute].get(mostCommon);
            //Print class attribute value
            System.out.println("Class attibute value is: " + mostCommonStr);

        } else System.out.println("The file doesn't exist.");
    }

    public void queryTreeOutput(String treeFile, String casesFile) {
        if (!treeFile.endsWith(".tree")) treeFile += ".tree";
        String fileOutStr;
        if (!casesFile.endsWith(".cases")) fileOutStr = casesFile + ".tmp";
        else {
            fileOutStr = casesFile.substring(0, casesFile.length() - 5) + "tmp";
        }

        File inputTreeFile = new File(treeFile);
        cid3 id3;

        FileWriter fileout = null;
        try {
            fileout = new FileWriter(fileOutStr, false);
        }
        catch (Exception e){
            System.err.println( "Error creating temporal file." + "\n");
            System.exit(1);
        }
        BufferedWriter fileBuf = new BufferedWriter(fileout);
        PrintWriter printOut = new PrintWriter(fileBuf);

        if (inputTreeFile.exists()) {
            id3 = deserializeFile(treeFile);
            System.out.print("\n");
            System.out.print("Tree file deserialized.");
            System.out.print("\n");

            FileInputStream inCases = null;

            try {
                if (!casesFile.endsWith(".cases")) casesFile += ".cases";
                File inputFile = new File(casesFile);
                inCases = new FileInputStream(inputFile);
            } catch ( Exception e) {
                System.err.println( "Unable to open cases file." + "\n");
                System.exit(1);
            }

            BufferedReader bin = new BufferedReader(new InputStreamReader(inCases));
            String input = null;
            StringTokenizer tokenizer;

            try {
                input = bin.readLine();
            }
            catch (Exception e){
                System.err.println( "Unable to read line: " + "\n");
                System.exit(1);
            }
            while(input != null) {
                if (input.trim().equals("")) continue;
                if (input.startsWith("//")) continue;

                tokenizer = new StringTokenizer(input, ",");
                int numtokens = tokenizer.countTokens();
                if (numtokens != id3.numAttributes - 1) {
                    System.err.println( "Expecting " + (id3.numAttributes - 1)  + " attributes");
                    System.exit(1);
                }

                DataPoint point = new DataPoint(id3.numAttributes);
                String next;
                for (int i=0; i < id3.numAttributes - 1; i++) {
                    next = tokenizer.nextToken().trim();
                    if(id3.attributeTypes[i] == AttributeType.Continuous) {
                        if (next.equals("?") || next.equals("NaN")) {
                            double value;
                            value = id3.meanValues[i];
                            point.attributes[i] = id3.getSymbolValue(i,value);
                        }

                        else
                        {
                            try {
                                point.attributes[i] = id3.getSymbolValue(i, Double.parseDouble(next));
                            }
                            catch (Exception e){
                                System.err.println( "Error reading continuous value in train data.");
                                System.exit(1);
                            }
                        }
                    }
                    else
                    if (id3.attributeTypes[i] == AttributeType.Discrete) {
                        if (next.equals("?") || next.equals("NaN")) {
                            point.attributes[i] = id3.mostCommonValues[i];
                        }
                        else point.attributes[i] = id3.getSymbolValue(i, next);
                    }
                }
                //Test the example point
                TreeNode node;
                node = id3.testExamplePoint(point, id3.root);
                boolean isEmpty = true;
                int caseClass;
                //Check if the node is empty, if so, return its parent most frequent class.
                for (int j = 0; j < node.frequencyClasses.length; j ++){
                    if (node.frequencyClasses[j] != 0){
                        isEmpty = false;
                        break;
                    }
                }
                //If node is empty
                if (isEmpty) caseClass =  id3.mostCommonFinal(node.parent);
                else caseClass = id3.mostCommonFinal(node);

                //Print line to output tmp file
                String classValue = (String) id3.domainsIndexToValue[id3.classAttribute].get(caseClass);
                String line = input + "," + classValue;
                printOut.write(line);
                printOut.println();

                //continue the loop
                try {
                    input = bin.readLine();
                }
                catch (Exception e){
                    System.err.println( "Unable to read line. " + "\n");
                    System.exit(1);
                }
            }
            printOut.close();
            System.out.print("\n");
            System.out.print("Results saved to tmp file.");
            System.out.print("\n");
        }
        else System.out.println("The tree file doesn't exist.");
    }

    public void queryRandomForestOutput(String rfFile, String casesFile) {
        if (!rfFile.endsWith(".forest")) rfFile += ".forest";
        String fileOutStr;
        if (!casesFile.endsWith(".cases")) fileOutStr = casesFile + ".tmp";
        else {
            fileOutStr = casesFile.substring(0, casesFile.length() - 5) + "tmp";
        }

        File inputForestFile = new File(rfFile);
        cid3 id3;

        FileWriter fileout = null;
        try {
            fileout = new FileWriter(fileOutStr, false);
        }
        catch (Exception e){
            System.err.println( "Error creating temporal file." + "\n");
            System.exit(1);
        }
        BufferedWriter fileBuf = new BufferedWriter(fileout);
        PrintWriter printOut = new PrintWriter(fileBuf);

        if (inputForestFile.exists()) {
            id3 = deserializeFile(rfFile);
            System.out.print("\n");
            System.out.print("Forest file deserialized.");
            System.out.print("\n");

            FileInputStream inCases = null;

            try {
                if (!casesFile.endsWith(".cases")) casesFile += ".cases";
                File inputFile = new File(casesFile);
                inCases = new FileInputStream(inputFile);
            } catch ( Exception e) {
                System.err.println( "Unable to open cases file." + "\n");
                System.exit(1);
            }

            BufferedReader bin = new BufferedReader(new InputStreamReader(inCases));
            String input = null;
            StringTokenizer tokenizer;

            try {
                input = bin.readLine();
            }
            catch (Exception e){
                System.err.println( "Unable to read line: " + "\n");
                System.exit(1);
            }
            while(input != null) {
                if (input.trim().equals("")) continue;
                if (input.startsWith("//")) continue;

                tokenizer = new StringTokenizer(input, ",");
                int numtokens = tokenizer.countTokens();
                if (numtokens != id3.numAttributes - 1) {
                    System.err.println( "Expecting " + (id3.numAttributes - 1)  + " attributes");
                    System.exit(1);
                }

                DataPoint point = new DataPoint(id3.numAttributes);
                String next;
                for (int i=0; i < id3.numAttributes - 1; i++) {
                    next = tokenizer.nextToken().trim();
                    if(id3.attributeTypes[i] == AttributeType.Continuous) {
                        if (next.equals("?") || next.equals("NaN")) {
                            double value;
                            value = id3.meanValues[i];
                            point.attributes[i] = id3.getSymbolValue(i,value);
                        }

                        else
                        {
                            try {
                                point.attributes[i] = id3.getSymbolValue(i, Double.parseDouble(next));
                            }
                            catch (Exception e){
                                System.err.println( "Error reading continuous value in train data.");
                                System.exit(1);
                            }
                        }
                    }
                    else
                    if (id3.attributeTypes[i] == AttributeType.Discrete) {
                        if (next.equals("?") || next.equals("NaN")) {
                            point.attributes[i] = id3.mostCommonValues[i];
                        }
                        else point.attributes[i] = id3.getSymbolValue(i, next);
                    }
                    //else
                    //if (id3.attributeTypes[i] == AttributeType.Ignore){
                    //    continue;
                    //}
                }
                //Check the created example against the random forest
                int[] classAttrValues = new int[id3.domainsIndexToValue[id3.classAttribute].size()];
                ArrayList<TreeNode> roots = id3.rootsRandomForest;
                TreeNode node;
                int resultClass = 0;

                for (int i = 0; i < roots.size(); i++){
                    node = id3.testExamplePoint(point, roots.get(i));
                    //Check if the node is empty, if so, return its parent most frequent class.
                    boolean isEmpty = true;
                    for (int j = 0; j < node.frequencyClasses.length; j ++){
                        if (node.frequencyClasses[j] != 0){
                            isEmpty = false;
                            break;
                        }
                    }
                    //If node is empty
                    if (isEmpty) classAttrValues[id3.mostCommonFinal(node.parent)]++;
                    else classAttrValues[id3.mostCommonFinal(node)]++;
                }
                //Voting now
                for (int i = 1; i < classAttrValues.length; i++){
                    if (classAttrValues[i] > resultClass)
                        resultClass = i;
                }
                //Print line to output tmp file
                String classValue = (String) id3.domainsIndexToValue[id3.classAttribute].get(resultClass);
                String line = input + "," + classValue;
                printOut.write(line);
                printOut.println();

                //continue the loop
                try {
                    input = bin.readLine();
                }
                catch (Exception e){
                    System.err.println( "Unable to read line. " + "\n");
                    System.exit(1);
                }
            }
            printOut.close();
            System.out.print("\n");
            System.out.print("Results saved to tmp file.");
            System.out.print("\n");
        }
        else System.out.println("The forest file doesn't exist.");
    }


    public void queryRandomForest(String file) {
        if (!file.endsWith(".forest")) file += ".forest";

        File inputFile = new File(file);
        Scanner in = new Scanner(System.in);
        cid3 id3;
        if (inputFile.exists()) {
            id3 = deserializeFile(file);
            System.out.print("\n");
            System.out.print("Random Forest file deserialized.");
            System.out.print("\n");
            DataPoint example = new DataPoint(id3.numAttributes);
            //Enter all attributes into an example except the class, that's why -1
            for (int i = 0; i < id3.numAttributes - 1; i ++){
                if (id3.attributeTypes[i] == AttributeType.Ignore) continue;
                if (id3.attributeTypes[i] == AttributeType.Discrete) {
                    System.out.print("\n");
                    System.out.print("Please enter attribute: " + id3.attributeNames[i]);
                    System.out.print("\n");
                    System.out.print("(possible values are: ");
                    String values = "";
                    ArrayList<String> valuesArray = new ArrayList<>();
                    for (int j = 0; j < id3.domainsIndexToValue[i].size(); j++) {
                        valuesArray.add((String) id3.domainsIndexToValue[i].get(j));
                    }
                    Collections.sort(valuesArray);
                    for (int j = 0; j < valuesArray.size(); j++){
                        values += valuesArray.get(j);
                        values += ", ";
                    }

                    values = "?, " + values.substring(0, values.length() - 2);
                    System.out.print(values + ")");
                    System.out.print("\n");

                    while(true) {
                        int value;
                        String s = in.nextLine();
                        if (s.equals("?")) {
                            value = id3.mostCommonValues[i];
                            example.attributes[i] = value;
                            break;
                        }
                        else
                        if (id3.domainsIndexToValue[i].containsValue(s)){
                            example.attributes[i] = id3.getSymbolValue(i,s);
                            break;
                        }
                        else
                            System.out.println("Please enter a valid value:");
                    }
                }
                else {
                    System.out.print("\n");
                    System.out.print("Please enter attribute: " + id3.attributeNames[i]);
                    System.out.print("\n");
                    while (true) {
                        String s = in.nextLine();
                        try {
                            double value;
                            if (s.equals("?")) value = id3.meanValues[i];
                            else value = Double.parseDouble(s);
                            example.attributes[i] = id3.getSymbolValue(i,value);
                            break;
                        } catch (Exception e) {
                            System.out.println("Please enter a valid value:");
                        }
                    }
                }
            }

            //Check the created example against the random forest
            int[] classAttrValues = new int[id3.domainsIndexToValue[id3.classAttribute].size()];
            ArrayList<TreeNode> roots = id3.rootsRandomForest;
            TreeNode node;
            int resultClass = 0;

            for (int i = 0; i < roots.size(); i++){
                node = id3.testExamplePoint(example, roots.get(i));
                //Check if the node is empty, if so, return its parent most frequent class.
                boolean isEmpty = true;
                for (int j = 0; j < node.frequencyClasses.length; j ++){
                    if (node.frequencyClasses[j] != 0){
                        isEmpty = false;
                        break;
                    }
                }
                //If node is empty
                if (isEmpty) classAttrValues[id3.mostCommonFinal(node.parent)]++;
                else classAttrValues[id3.mostCommonFinal(node)]++;
            }
            //Voting now
            for (int i = 1; i < classAttrValues.length; i++){
                if (classAttrValues[i] > resultClass)
                    resultClass = i;
            }
            //Print the answer
            String mostCommonStr = (String) id3.domainsIndexToValue[id3.classAttribute].get(resultClass);
            System.out.print("\n");
            System.out.println("Class attibute value is: " + mostCommonStr);
        }
        else System.out.println("The file doesn't exist.");
    }

    /* Here is the definition of the main function */
    public static void main(String[] args) {

        cid3 me = new cid3();

        //Initialize values
        me.isCrossValidation = false;
        me.testDataExists = false;
        me.isRandomForest = false;

        Options options = new Options();

        Option help = new Option("h", "help", false, "print this message");
        help.setRequired(false);
        options.addOption(help);

        Option save = new Option("s", "save", false, "save tree/random forest");
        save.setRequired(false);
        options.addOption(save);

        Option partition = new Option("p", "partition", false, "partition train/test data");
        save.setRequired(false);
        options.addOption(partition);

        Option criteria = new Option("c", "criteria", true, "input criteria: c[ertainty], e[ntropy], g[ini]");
        criteria.setRequired(false);
        options.addOption(criteria);

        Option file = new Option("f", "file", true, "input file");
        file.setRequired(true);
        options.addOption(file);

        Option crossValidation = new Option("v", "validation", false, "create 10-fold cross-validation");
        crossValidation.setRequired(false);
        options.addOption(crossValidation);

        Option randomForest = new Option("r", "forest", true, "create random forest, enter # of trees");
        randomForest.setRequired(false);
        options.addOption(randomForest);

        Option query = new Option("q", "query", true, "query model, enter: t[ree] or r[andom forest]");
        query.setRequired(false);
        options.addOption(query);

        Option output = new Option("o", "output", true, "output file");
        query.setRequired(false);
        options.addOption(output);

        //Declare parser and formatter
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].contains(" -h ") || args[i].contains(" --help ")){
                    //Print help message
                    formatter.printHelp("java -jar cid3.jar", options);
                    System.exit(1);
                }
            }
            cmd = parser.parse(options, args);
        }
        catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("java -jar cid3.jar", options);
            System.exit(1);
        }

        //Set criteria
        //me.criteria = Criteria.Certainty;
        if (cmd.hasOption("criteria")) {
            String strCriteria = cmd.getOptionValue("criteria");
            if (strCriteria.equals("C") || strCriteria.equals("c")) me.criteria = Criteria.Certainty;
            else if (strCriteria.equals("G") || strCriteria.equals("g")) me.criteria = Criteria.Gini;
            else if (strCriteria.equals("E") || strCriteria.equals("e")) me.criteria = Criteria.Entropy;
            else{
                formatter.printHelp("java -jar cid3.jar", options);
                System.exit(1);
            }
        }

        //Set file path
        String inputFilePath = cmd.getOptionValue("file");
        String originalInputFilePath = inputFilePath;

        //Set validation
        me.isCrossValidation = cmd.hasOption("validation");

        //Set split data
        me.splitTrainData = cmd.hasOption("partition");

        //Set Random Forest
        if (cmd.hasOption("forest")){
            String numberOfTrees = cmd.getOptionValue("forest");
            try {
                me.numberOfTrees = Integer.parseInt(numberOfTrees);
            }
            catch (Exception e){
                System.out.print("Error: Incorrect number of trees");
                System.out.print("\n");
                System.exit(1);
            }
            me.isRandomForest = true;
        }
        else me.isRandomForest = false;

        //Print help message
        if (cmd.hasOption("help")) {
            formatter.printHelp("java -jar cid3.jar", options);
            System.exit(1);
        }

        //Show application title
        System.out.print("\n");
        int dayInMonth = java.time.LocalDateTime.now().getDayOfMonth();
        String day = java.time.LocalDateTime.now().getDayOfWeek().name().toLowerCase();
        day = day.substring(0, 1).toUpperCase() + day.substring(1);
        String month = java.time.LocalDateTime.now().getMonth().name().toLowerCase();
        month = month.substring(0, 1).toUpperCase() + month.substring(1);

        DateTimeFormatter time = DateTimeFormatter.ofPattern("hh:mm:ss a");
        String timeString = java.time.LocalDateTime.now().format(time);

        System.out.print("CID3 [Version 1.0]" + "              " + day + " " + month + " " + dayInMonth + " " + timeString);
        System.out.print("\n");
        System.out.print("------------------");
        System.out.print("\n");

        //if is a query, deserialize file and query model
        String outputFilePath;
        if (cmd.hasOption("query")) {
            String model = cmd.getOptionValue("query");
            if (model.equals("t")) {
                if (cmd.hasOption("output")){
                    outputFilePath = cmd.getOptionValue("output");
                    me.queryTreeOutput(originalInputFilePath, outputFilePath);
                }
                else me.queryTree(originalInputFilePath);
            }
            else if (model.equals("r")) {
                if (cmd.hasOption("output")){
                    outputFilePath = cmd.getOptionValue("output");
                    me.queryRandomForestOutput(originalInputFilePath, outputFilePath);
                }
                else me.queryRandomForest(originalInputFilePath);
            }
        }
        else {
            //Check if test data exists
            if (!inputFilePath.endsWith(".data")) inputFilePath += ".data";
            String nameTestData = inputFilePath.substring(0, inputFilePath.length() - 4);
            nameTestData = nameTestData + "test";

            File inputFile = new File(nameTestData);
            me.testDataExists = inputFile.exists();

            //Read data
            me.readData(inputFilePath);
            //Set global variable
            me.fileName = inputFilePath;
            //Read test data
            if (me.testDataExists) me.readTestData(nameTestData);

            //Create a Tree or a Random Forest for saving to disk
            if (cmd.hasOption("save")) {
                me.trainData.addAll(me.testData);
                me.root.data = me.trainData;
                me.testData.clear();
                me.setMeanValues();
                me.setMostCommonValues();
                me.imputeMissing();
                if (me.isRandomForest) {
                    me.createRandomForest(me.root.data, me.rootsRandomForest, false);
                    System.out.print("\n");
                    System.out.print("Saving random forest...");
                    me.createFileRF();
                    System.out.print("\n");
                    System.out.print("Random forest saved to disk.");
                    System.out.print("\n");
                } else {
                    me.createDecisionTree();
                    System.out.print("\n");
                    System.out.print("Saving tree...");
                    me.createFile();
                    System.out.print("\n");
                    System.out.print("Tree saved to disk.");
                    System.out.print("\n");
                }
            } //Create CV, RF, Tree without saving
            else {
                me.setMeanValues();
                me.setMostCommonValues();
                me.imputeMissing();
                if (me.isCrossValidation && me.isRandomForest) me.createCrossValidationRF();
                else if (me.isCrossValidation) me.createCrossValidation();
                else if (me.isRandomForest) me.createRandomForest(me.root.data, me.rootsRandomForest, false);
                else me.createDecisionTree();
            }
        }
    }
}