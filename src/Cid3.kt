import java.io.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.*
import kotlin.system.exitProcess
import org.apache.commons.cli.*

class Cid3 : Serializable {
    enum class AttributeType {
        Discrete, Continuous, Ignore
    }
    // The number of attributes including the output attribute
    private var numAttributes = 0
    // The names of all attributes.  It is an array of dimension numAttributes.  The last attribute is the output attribute
    private lateinit var attributeNames: Array<String?>
    private lateinit var attributeTypes: Array<AttributeType?>
    private var classAttribute = 0
    private lateinit var meanValues: DoubleArray
    private lateinit var mostCommonValues: IntArray
    lateinit var fileName: String
    private var seed: Long = 13579

    //int maxThreads = 500;
    //transient ArrayList<Thread> globalThreads = new ArrayList<>();

    /* Possible values for each attribute is stored in a vector.  domains is an array of dimension numAttributes.
        Each element of this array is a vector that contains values for the corresponding attribute
        domains[0] is a vector containing the values of the 0-th attribute, etc..
        The last attribute is the output attribute
    */
    private lateinit var domainsIndexToValue: ArrayList<HashMap<Int, Any>>
    private lateinit var domainsValueToIndex: ArrayList<HashMap<Any, Int>>

    enum class Criteria {
        Entropy, Certainty, Gini
    }

    /*  The class to represent a data point consisting of numAttributes values of attributes  */
    class DataPoint(numAttributes: Int) : Serializable {
        /* The values of all attributes stored in this array.  i-th element in this array
           is the index to the element in the vector domains representing the symbolic value of
           the attribute.  For example, if attributes[2] is 1, then the actual value of the
           2-nd attribute is obtained by domains[2].get(1).  This representation makes
           comparing values of attributes easier - it involves only integer comparison and
           no string comparison.
           The last attribute is the output attribute
        */
        var attributes: IntArray = IntArray(numAttributes)

    }

    //This class will be used to calculate all probabilities in one pass.
    inner class Probabilities(attribute: Int) : Serializable {
        var prob: DoubleArray = DoubleArray(domainsIndexToValue[attribute].size)
        var probCAndA: Array<DoubleArray> = Array(domainsIndexToValue[attribute].size) { DoubleArray(domainsIndexToValue[classAttribute].size) }
        var probCGivenA: Array<DoubleArray> = Array(domainsIndexToValue[attribute].size) { DoubleArray(domainsIndexToValue[classAttribute].size) }

    }

    //This is a utility class to return the certainty and threshold of continuous attributes.
    class Certainty(var certainty: Double, var threshold: Double) : Serializable

    @Transient
    var testData = ArrayList<DataPoint>()

    @Transient
    var trainData = ArrayList<DataPoint>()

    @Transient
    var crossValidationChunks = ArrayList<ArrayList<DataPoint>>()

    @Transient
    var testDataExists = false

    @Transient
    var splitTrainData = false

    @Transient
    var isRandomForest = false

    @Transient
    var isCrossValidation = false

    @Transient
    var numberOfTrees = 1

    /* The class to represent a node in the decomposition tree.
     */
    class TreeNode : Serializable {
        var certaintyUsedToDecompose = 0.0
        // The set of data points if this is a leaf node
        @Transient
        var data: ArrayList<DataPoint> = ArrayList()
        //This is for saving time when calculating most common class
        lateinit var frequencyClasses: IntArray
        // If this is not a leaf node, the attribute that is used to divide the set of data points
        var decompositionAttribute = 0
        // the attribute-value that is used to divide the parent node
        var decompositionValue = 0
        var decompositionValueContinuous = ""
        var decompositionValueContinuousSymbol = ""
        var thresholdContinuous = 0.0
        // If this is not a leaf node, references to the children nodes
        var children: ArrayList<TreeNode>? = null
        // The parent to this node.  The root has parent == null
        var parent: TreeNode? = null

    }

    /*  The root of the decomposition tree  */
    var root = TreeNode()
    var rootsRandomForest = ArrayList<TreeNode>()

    @Transient
    var cvRandomForests = ArrayList<ArrayList<TreeNode>>()

    @Transient
    var rootsCrossValidation = ArrayList<TreeNode>()

    @Transient
    var criteria = Criteria.Certainty

    @Transient
    var totalRules = 0

    @Transient
    var totalNodes = 0

    /*  This function returns an integer corresponding to the symbolic value of the attribute.
        If the symbol does not exist in the domain, the symbol is added to the domain of the attribute
    */
    private fun getSymbolValue(attribute: Int, symbol: Any): Int {
        return if (domainsValueToIndex[attribute][symbol] != null)
            domainsValueToIndex[attribute][symbol]!!
        else {
            val size = domainsIndexToValue[attribute].size
            domainsIndexToValue[attribute][size] = symbol
            domainsValueToIndex[attribute][symbol] = size
            size
        }
    }

    // Returns the most common class for the specified node
    private fun getMostCommonClass(n: TreeNode?): Int {
        val numValuesClass = domainsIndexToValue[classAttribute].size
        var value = n!!.frequencyClasses[0]
        var result = 0
        for (i in 1 until numValuesClass) {
            if (n.frequencyClasses[i] > value) {
                value = n.frequencyClasses[i]
                result = i
            }
        }
        return result
    }

    /*  Returns a subset of data, in which the value of the specified attribute of all data points is the specified value  */
    private fun getSubset(data: ArrayList<DataPoint>, attribute: Int, value: Int): DataFrequencies {
        val subset = ArrayList<DataPoint>()
        val frequencies = IntArray(domainsIndexToValue[classAttribute].size)
        for (point in data) {
            if (point.attributes[attribute] == value) {
                subset.add(point)
                frequencies[point.attributes[classAttribute]]++
            }
        }
        return DataFrequencies(subset, frequencies)
    }

    private fun getFrequencies(data: ArrayList<DataPoint>): IntArray {
        val frequencies = IntArray(domainsIndexToValue[classAttribute].size)
        for (point in data) {
            frequencies[point.attributes[classAttribute]]++
        }
        return frequencies
    }

    private fun getSubsetsBelowAndAbove(data: ArrayList<DataPoint>, attribute: Int, value: Double): Tuple<DataFrequencies, DataFrequencies> {
        val subsetBelow = ArrayList<DataPoint>()
        val subsetAbove = ArrayList<DataPoint>()
        val frequenciesBelow = IntArray(domainsIndexToValue[classAttribute].size)
        val frequenciesAbove = IntArray(domainsIndexToValue[classAttribute].size)
        for (point in data) {
            if (domainsIndexToValue[attribute][point.attributes[attribute]] as Double <= value) {
                subsetBelow.add(point)
                frequenciesBelow[point.attributes[classAttribute]]++
            } else {
                subsetAbove.add(point)
                frequenciesAbove[point.attributes[classAttribute]]++
            }
        }
        return Tuple(DataFrequencies(subsetBelow, frequenciesBelow), DataFrequencies(subsetAbove, frequenciesAbove))
    }

    //This is the final form of the certainty function.
    private fun calculateCertainty(data: ArrayList<DataPoint>, givenThatAttribute: Int): Certainty {
        val numData = data.size
        if (numData == 0) return Certainty(0.0, 0.0)
        val numValuesClass = domainsIndexToValue[classAttribute].size
        val numValuesGivenAtt = domainsIndexToValue[givenThatAttribute].size

        //If attribute is discrete
        return if (attributeTypes[givenThatAttribute] == AttributeType.Discrete) {
            val probabilities = calculateAllProbabilities(data)
            var sum: Double
            var sum2 = 0.0
            var probability: Double
            var probabilityCAndA: Double
            for (j in 0 until numValuesGivenAtt) {
                probability = probabilities[givenThatAttribute]!!.prob[j]
                sum = 0.0
                for (i in 0 until numValuesClass) {
                    probabilityCAndA = probabilities[givenThatAttribute]!!.probCAndA[j][i]
                    sum += abs(probabilityCAndA - 1.0 * probability / numValuesClass)
                }
                sum2 += sum
            }
            Certainty(sum2, 0.0)
        } else {
            var finalThreshold = 0.0
            var totalCertainty: Double
            var finalTotalCertainty = 0.0
            /*---------------------------------------------------------------------------------------------------------*/
            //Implementation of thresholds using a sorted set
            val attributeValuesSet: SortedSet<Double> = TreeSet()
            val attributeToClass = HashMap<Double, Tuple<Int, Boolean>>()
            for (point in data) {
                val attribute = domainsIndexToValue[givenThatAttribute][point.attributes[givenThatAttribute]] as Double
                val theClass = point.attributes[classAttribute]
                attributeValuesSet.add(attribute)
                val tuple = attributeToClass[attribute]
                if (tuple != null) {
                    if (tuple.x != theClass && tuple.y) attributeToClass[attribute] = Tuple(theClass, false)
                } else attributeToClass[attribute] = Tuple(theClass, true)
            }
            val it: Iterator<Double> = attributeValuesSet.iterator()
            var attributeValue = it.next()
            var attributeClass1 = attributeToClass[attributeValue]!!
            var theClass = attributeClass1.x
            val thresholds = ArrayList<Threshold>()
            while (it.hasNext()) {
                val attributeValue2 = it.next()
                val attributeClass2 = attributeToClass[attributeValue2]!!
                val theClass2 = attributeClass2.x
                if (theClass2 != theClass || !attributeClass2.y || !attributeClass1.y) {
                    //Add threshold
                    val median = (attributeValue + attributeValue2) / 2
                    thresholds.add(Threshold(median, arrayOfNulls(numValuesClass)))
                }
                //Set new point
                attributeValue = attributeValue2
                theClass = theClass2
                attributeClass1 = attributeClass2
            }

            /*---------------------------------------------------------------------------------------------------------*/
            //If there are no thresholds return zero.
            if (thresholds.isEmpty()) return Certainty(0.0, 0.0)

            //This trick reduces the possible thresholds to just ONE 0r TWO, dramatically improving running times!
            //=========================================================
            val centerThresholdIndex = thresholds.size / 2
            val centerThreshold: Threshold
            val centerThreshold1: Threshold
            when {
                thresholds.size == 1 -> {
                    centerThreshold = thresholds[0]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                }
                thresholds.size % 2 != 0 -> {
                    centerThreshold = thresholds[centerThresholdIndex]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                }
                else -> {
                    centerThreshold = thresholds[centerThresholdIndex]
                    centerThreshold1 = thresholds[centerThresholdIndex - 1]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                    thresholds.add(centerThreshold1)
                }
            }
            //=========================================================
            var probABelow: Double
            var probAAbove: Double
            var probCAndABelow: Double
            var probCAndAAbove: Double
            var certaintyBelow: Double
            var certaintyAbove: Double
            //Loop through the data just one time
            for (point in data) {
                //For each threshold count data to get prob and probC_And_A
                val theClass1 = point.attributes[classAttribute]
                for (iThreshold in thresholds) {
                    if (iThreshold.sumsClassesAndAttribute[theClass1] == null) iThreshold.sumsClassesAndAttribute[theClass1] = SumBelowAndAbove(0, 0)
                    if (domainsIndexToValue[givenThatAttribute][point.attributes[givenThatAttribute]] as Double <= iThreshold.value) {
                        iThreshold.sumABelow++
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[theClass1]!!.below++
                    } else {
                        iThreshold.sumAAbove++
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[theClass1]!!.above++
                    }
                }
            }

            //Now calculate probabilities
            for (threshold in thresholds) {
                //Calculate prob
                probABelow = 1.0 * threshold.sumABelow / numData
                probAAbove = 1.0 * threshold.sumAAbove / numData

                //Reset the certainty
                certaintyBelow = 0.0
                certaintyAbove = 0.0
                for (c in 0 until numValuesClass) {
                    if (threshold.sumsClassesAndAttribute[c] != null) {
                        probCAndABelow = 1.0 * threshold.sumsClassesAndAttribute[c]!!.below / numData
                        probCAndAAbove = 1.0 * threshold.sumsClassesAndAttribute[c]!!.above / numData
                    } else {
                        probCAndABelow = 0.0
                        probCAndAAbove = 0.0
                    }
                    certaintyBelow += abs(probCAndABelow - probABelow / numValuesClass)
                    certaintyAbove += abs(probCAndAAbove - probAAbove / numValuesClass)
                }
                //Calculate totals
                totalCertainty = certaintyBelow + certaintyAbove
                if (finalTotalCertainty < totalCertainty) {
                    finalTotalCertainty = totalCertainty
                    finalThreshold = threshold.value
                }
            }
            Certainty(finalTotalCertainty, finalThreshold)
        }
    }

    //This is Entropy.
    private fun calculateEntropy(data: ArrayList<DataPoint>, givenThatAttribute: Int): Certainty {
        val numData = data.size
        if (numData == 0) return Certainty(0.0, 0.0)
        val numValuesClass = domainsIndexToValue[classAttribute].size
        val numValuesGivenAtt = domainsIndexToValue[givenThatAttribute].size
        //If attribute is discrete
        return if (attributeTypes[givenThatAttribute] == AttributeType.Discrete) {
            val probabilities = calculateAllProbabilities(data)
            var sum: Double
            var sum2 = 0.0
            var probability: Double
            var probabilityCGivenA: Double
            for (j in 0 until numValuesGivenAtt) {
                probability = probabilities[givenThatAttribute]!!.prob[j]
                sum = 0.0
                for (i in 0 until numValuesClass) {
                    probabilityCGivenA = probabilities[givenThatAttribute]!!.probCGivenA[j][i]
                    if (probabilityCGivenA != 0.0) sum += -probabilityCGivenA * ln(probabilityCGivenA)
                }
                sum2 += probability * sum
            }
            Certainty(sum2, 0.0)
        } else {
            var finalThreshold = 0.0
            var totalEntropy: Double
            var finalTotalEntropy = 0.0
            /*---------------------------------------------------------------------------------------------------------*/
            //Implementation of thresholds using a sorted set
            val attributeValuesSet: SortedSet<Double> = TreeSet()
            val attributeToClass = HashMap<Double, Tuple<Int, Boolean>>()
            for (point in data) {
                val attribute = domainsIndexToValue[givenThatAttribute][point.attributes[givenThatAttribute]] as Double
                val theClass = point.attributes[classAttribute]
                attributeValuesSet.add(attribute)
                val tuple = attributeToClass[attribute]
                if (tuple != null) {
                    if (tuple.x != theClass && tuple.y) attributeToClass[attribute] = Tuple(theClass, false)
                } else attributeToClass[attribute] = Tuple(theClass, true)
            }
            val it: Iterator<Double> = attributeValuesSet.iterator()
            var attributeValue = it.next()
            var attributeClass1 = attributeToClass[attributeValue]!!
            var theClass = attributeClass1.x
            val thresholds = ArrayList<Threshold>()
            while (it.hasNext()) {
                val attributeValue2 = it.next()
                val attributeClass2 = attributeToClass[attributeValue2]!!
                val theClass2 = attributeClass2.x
                if (theClass2 != theClass || !attributeClass2.y || !attributeClass1.y) {
                    //Add threshold
                    val median = (attributeValue + attributeValue2) / 2
                    thresholds.add(Threshold(median, arrayOfNulls(numValuesClass)))
                }
                //Set new point
                attributeValue = attributeValue2
                theClass = theClass2
                attributeClass1 = attributeClass2
            }
            /*---------------------------------------------------------------------------------------------------------*/
            //If there are no thresholds return -1.
            if (thresholds.isEmpty()) return Certainty(-1.0, 0.0)
            //This trick reduces the possible thresholds to just ONE 0r TWO, dramatically improving running times!
            //=========================================================
            val centerThresholdIndex = thresholds.size / 2
            val centerThreshold: Threshold
            val centerThreshold1: Threshold
            when {
                thresholds.size == 1 -> {
                    centerThreshold = thresholds[0]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                }
                thresholds.size % 2 != 0 -> {
                    centerThreshold = thresholds[centerThresholdIndex]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                }
                else -> {
                    centerThreshold = thresholds[centerThresholdIndex]
                    centerThreshold1 = thresholds[centerThresholdIndex - 1]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                    thresholds.add(centerThreshold1)
                }
            }
            //=========================================================
            var probABelow: Double
            var probAAbove: Double
            var probCAndABelow: Double
            var probCAndAAbove: Double
            var entropyBelow: Double
            var entropyAbove: Double
            var selected = false

            //Loop through the data just one time
            for (point in data) {
                //For each threshold count data to get prob and probC_And_A
                val pointClass = point.attributes[classAttribute]
                for (iThreshold in thresholds) {
                    if (iThreshold.sumsClassesAndAttribute[pointClass] == null) iThreshold.sumsClassesAndAttribute[pointClass] = SumBelowAndAbove(0, 0)
                    if ((domainsIndexToValue[givenThatAttribute][point.attributes[givenThatAttribute]] as Double)<iThreshold.value) {
                        iThreshold.sumABelow++
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[pointClass]!!.below++
                    } else {
                        iThreshold.sumAAbove++
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[pointClass]!!.above++
                    }
                }
            }
            //Now calculate probabilities
            for (threshold in thresholds) {
                //Calculate prob
                probABelow = 1.0 * threshold.sumABelow / numData
                probAAbove = 1.0 * threshold.sumAAbove / numData
                //Reset the entropy
                entropyBelow = 0.0
                entropyAbove = 0.0
                for (c in 0 until numValuesClass) {
                    if (threshold.sumsClassesAndAttribute[c] != null) {
                        probCAndABelow = 1.0 * threshold.sumsClassesAndAttribute[c]!!.below / numData
                        probCAndAAbove = 1.0 * threshold.sumsClassesAndAttribute[c]!!.above / numData
                    } else {
                        probCAndABelow = 0.0
                        probCAndAAbove = 0.0
                    }
                    if (probCAndABelow != 0.0 && probABelow != 0.0) entropyBelow += -probCAndABelow / probABelow * ln(probCAndABelow / probABelow)
                    if (probCAndAAbove != 0.0 && probAAbove != 0.0) entropyAbove += -probCAndAAbove / probAAbove * ln(probCAndAAbove / probAAbove)
                }
                //Calculate totals
                totalEntropy = entropyBelow * probABelow + entropyAbove * probAAbove
                if (!selected) {
                    selected = true
                    finalTotalEntropy = totalEntropy
                    finalThreshold = threshold.value
                } else {
                    if (finalTotalEntropy > totalEntropy) {
                        finalTotalEntropy = totalEntropy
                        finalThreshold = threshold.value
                    }
                }
            }
            Certainty(finalTotalEntropy, finalThreshold)
        }
    }

    //This is Gini.
    private fun calculateGini(data: ArrayList<DataPoint>, givenThatAttribute: Int): Certainty {
        val numData = data.size
        if (numData == 0) return Certainty(0.0, 0.0)
        val numValuesClass = domainsIndexToValue[classAttribute].size
        val numValuesGivenAtt = domainsIndexToValue[givenThatAttribute].size
        //If attribute is discrete
        return if (attributeTypes[givenThatAttribute] == AttributeType.Discrete) {
            val probabilities = calculateAllProbabilities(data)
            var sum: Double
            var sum2 = 0.0
            var probability: Double
            var probabilityCGivenA: Double
            var gini: Double
            for (j in 0 until numValuesGivenAtt) {
                probability = probabilities[givenThatAttribute]!!.prob[j]
                sum = 0.0
                for (i in 0 until numValuesClass) {
                    probabilityCGivenA = probabilities[givenThatAttribute]!!.probCGivenA[j][i]
                    sum += probabilityCGivenA.pow(2.0)
                }
                gini = 1 - sum
                sum2 += probability * gini
            }
            Certainty(sum2, 0.0)
        } else {
            var finalThreshold = 0.0
            var totalGini: Double
            var finalTotalGini = 0.0
            /*---------------------------------------------------------------------------------------------------------*/
            //Implementation of thresholds using a sorted set
            val attributeValuesSet: SortedSet<Double> = TreeSet()
            val attributeToClass = HashMap<Double, Tuple<Int, Boolean>>()
            for (point in data) {
                val attribute = domainsIndexToValue[givenThatAttribute][point.attributes[givenThatAttribute]] as Double
                val theClass = point.attributes[classAttribute]
                attributeValuesSet.add(attribute)
                val tuple = attributeToClass[attribute]
                if (tuple != null) {
                    if (tuple.x != theClass && tuple.y) attributeToClass[attribute] = Tuple(theClass, false)
                } else attributeToClass[attribute] = Tuple(theClass, true)
            }
            val it: Iterator<Double> = attributeValuesSet.iterator()
            var attributeValue = it.next()
            var attributeClass1 = attributeToClass[attributeValue]!!
            var theClass = attributeClass1.x
            val thresholds = ArrayList<Threshold>()
            while (it.hasNext()) {
                val attributeValue2 = it.next()
                val attributeClass2 = attributeToClass[attributeValue2]!!
                val theClass2 = attributeClass2.x
                if (theClass2 != theClass || !attributeClass2.y || !attributeClass1.y) {
                    //Add threshold
                    val median = (attributeValue + attributeValue2) / 2
                    thresholds.add(Threshold(median, arrayOfNulls(numValuesClass)))
                }
                //Set new point
                attributeValue = attributeValue2
                theClass = theClass2
                attributeClass1 = attributeClass2
            }
            /*---------------------------------------------------------------------------------------------------------*/
            //If there are no thresholds return -1.
            if (thresholds.isEmpty()) return Certainty(-1.0, 0.0)
            //This trick reduces the possible thresholds to just ONE 0r TWO, dramatically improving running times!
            //=========================================================
            val centerThresholdIndex = thresholds.size / 2
            val centerThreshold: Threshold
            val centerThreshold1: Threshold
            when {
                thresholds.size == 1 -> {
                    centerThreshold = thresholds[0]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                }
                thresholds.size % 2 != 0 -> {
                    centerThreshold = thresholds[centerThresholdIndex]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                }
                else -> {
                    centerThreshold = thresholds[centerThresholdIndex]
                    centerThreshold1 = thresholds[centerThresholdIndex - 1]
                    thresholds.clear()
                    thresholds.add(centerThreshold)
                    thresholds.add(centerThreshold1)
                }
            }
            //=========================================================
            var probABelow: Double
            var probAAbove: Double
            var probCAndABelow: Double
            var probCAndAAbove: Double
            var giniBelow: Double
            var giniAbove: Double
            var selected = false

            //Loop through the data just one time
            for (point in data) {
                //For each threshold count data to get prob and probC_And_A
                val pointClass = point.attributes[classAttribute]
                for (iThreshold in thresholds) {
                    if (iThreshold.sumsClassesAndAttribute[pointClass] == null) iThreshold.sumsClassesAndAttribute[pointClass] = SumBelowAndAbove(0, 0)
                    if ((domainsIndexToValue[givenThatAttribute][point.attributes[givenThatAttribute]] as Double)<iThreshold.value) {
                        iThreshold.sumABelow++
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[pointClass]!!.below++
                    } else {
                        iThreshold.sumAAbove++
                        //Next calculate probability of c and a
                        iThreshold.sumsClassesAndAttribute[pointClass]!!.above++
                    }
                }
            }
            //Now calculate probabilities
            for (threshold in thresholds) {
                //Calculate prob
                probABelow = 1.0 * threshold.sumABelow / numData
                probAAbove = 1.0 * threshold.sumAAbove / numData
                //Reset the gini
                giniBelow = 0.0
                giniAbove = 0.0
                for (c in 0 until numValuesClass) {
                    if (threshold.sumsClassesAndAttribute[c] != null) {
                        probCAndABelow = 1.0 * threshold.sumsClassesAndAttribute[c]!!.below / numData
                        probCAndAAbove = 1.0 * threshold.sumsClassesAndAttribute[c]!!.above / numData
                    } else {
                        probCAndABelow = 0.0
                        probCAndAAbove = 0.0
                    }
                    giniBelow += (probCAndABelow / probABelow).pow(2.0)
                    giniAbove += (probCAndAAbove / probAAbove).pow(2.0)
                }
                //Calculate totals
                giniBelow = 1 - giniBelow
                giniAbove = 1 - giniAbove
                totalGini = giniBelow * probABelow + giniAbove * probAAbove
                if (!selected) {
                    selected = true
                    finalTotalGini = totalGini
                    finalThreshold = threshold.value
                } else {
                    if (finalTotalGini > totalGini) {
                        finalTotalGini = totalGini
                        finalThreshold = threshold.value
                    }
                }
            }
            Certainty(finalTotalGini, finalThreshold)
        }
    }

    //This method calculates all probabilities in one run
    private fun calculateAllProbabilities(data: ArrayList<DataPoint>): Array<Probabilities?> {
        val numData = data.size
        val probabilities = arrayOfNulls<Probabilities>(numAttributes - 1)

        //Initialize the array
        for (j in 0 until numAttributes - 1) {
            if (attributeTypes[j] == AttributeType.Ignore) continue
            val p = Probabilities(j)
            probabilities[j] = p
        }
        //Count occurrences
        for (point in data) {
            for (j in 0 until point.attributes.size - 1) {
                if (attributeTypes[j] == AttributeType.Ignore) continue
                probabilities[j]!!.prob[point.attributes[j]]++
                probabilities[j]!!.probCAndA[point.attributes[j]][point.attributes[classAttribute]]++
            }
        }
        // Divide all values by total data size to get probabilities.
        var current: Probabilities?
        for (i in probabilities.indices) {
            if (attributeTypes[i] == AttributeType.Ignore) continue
            current = probabilities[i]
            for (j in current!!.prob.indices) {
                current.prob[j] = current.prob[j] / numData
            }
            for (j in current.probCAndA.indices) {
                for (k in current.probCAndA[j].indices) {
                    current.probCAndA[j][k] = current.probCAndA[j][k] / numData
                }
            }
            //Calculate ProbC_Given_A
            for (j in current.probCGivenA.indices) {
                for (k in current.probCGivenA[j].indices) {
                    current.probCGivenA[j][k] = current.probCAndA[j][k] / current.prob[j]
                }
            }
        }
        return probabilities
    }

    /*  This function checks if the specified attribute is used to decompose the data set
        in any of the parents of the specified node in the decomposition tree.
        Recursively checks the specified node as well as all parents
    */
    private fun alreadyUsedToDecompose(node: TreeNode?, attribute: Int): Boolean {
        if (node!!.children != null) {
            if (node.decompositionAttribute == attribute) return true
        }
        return if (node.parent == null) false else alreadyUsedToDecompose(node.parent, attribute)
    }

    private fun stopConditionAllClassesEqual(frequencyClasses: IntArray): Boolean {
        val numValuesClass = domainsIndexToValue[classAttribute].size
        var oneClassIsPresent = false
        for (i in 0 until numValuesClass) {
            if (frequencyClasses[i] != 0) {
                oneClassIsPresent = if (!oneClassIsPresent) true else return false
            }
        }
        return true
    }

    /*  This function decomposes the specified node according to the id3 algorithm.
    Recursively divides all children nodes until it is not possible to divide any further  */
    private fun decomposeNode(node: TreeNode, selectedAttributes: ArrayList<Int>, mySeed: Long) {
        var selectedAttributesLocal = selectedAttributes
        var bestCertainty = Certainty(0.0, 0.0)
        var selected = false
        var selectedAttribute = 0
        if (criteria == Criteria.Certainty) {
            if (node.data.size <= 1) return
            if (stopConditionAllClassesEqual(node.frequencyClasses)) return
            var certainty: Certainty
            for (selectedAtt in selectedAttributesLocal) {
                if (classAttribute == selectedAtt) continue
                if (attributeTypes[selectedAtt] == AttributeType.Discrete && alreadyUsedToDecompose(node, selectedAtt)) continue
                certainty = calculateCertainty(node.data, selectedAtt)
                if (certainty.certainty == 0.0) continue
                //Select best attribute
                if (certainty.certainty > bestCertainty.certainty) {
                    selected = true
                    bestCertainty = certainty
                    selectedAttribute = selectedAtt
                }
            }
            if (!selected || bestCertainty.certainty == 0.0) return
        } else if (criteria == Criteria.Entropy) {
            if (node.data.size <= 1) return
            if (stopConditionAllClassesEqual(node.frequencyClasses)) return
            /*  In the following two loops, the best attribute is located which
                        causes maximum increase in information*/
            var entropy: Certainty
            for (selectedAtt in selectedAttributesLocal) {
                if (classAttribute == selectedAtt) continue
                if (attributeTypes[selectedAtt] == AttributeType.Discrete && alreadyUsedToDecompose(node, selectedAtt)) continue
                entropy = calculateEntropy(node.data, selectedAtt)
                if (entropy.certainty == -1.0) continue
                if (!selected) {
                    selected = true
                    bestCertainty = entropy
                    selectedAttribute = selectedAtt
                } else {
                    if (entropy.certainty < bestCertainty.certainty) {
                        bestCertainty = entropy
                        selectedAttribute = selectedAtt
                    }
                }
            }
            if (!selected) return
        } else if (criteria == Criteria.Gini) {
            if (node.data.size <= 1) return
            if (stopConditionAllClassesEqual(node.frequencyClasses)) return
            /*  In the following two loops, the best attribute is located which
                            causes maximum increase in information*/
            var gini: Certainty
            for (selectedAtt in selectedAttributesLocal) {
                if (classAttribute == selectedAtt) continue
                if (attributeTypes[selectedAtt] == AttributeType.Discrete && alreadyUsedToDecompose(node, selectedAtt)) continue
                gini = calculateGini(node.data, selectedAtt)
                if (gini.certainty == -1.0) continue
                if (!selected) {
                    selected = true
                    bestCertainty = gini
                    selectedAttribute = selectedAtt
                } else {
                    if (gini.certainty < bestCertainty.certainty) {
                        bestCertainty = gini
                        selectedAttribute = selectedAtt
                    }
                }
            }
            if (!selected) return
        }
        node.certaintyUsedToDecompose = bestCertainty.certainty

        //if attribute is discrete
        if (attributeTypes[selectedAttribute] == AttributeType.Discrete) {
            // Now divide the dataset using the selected attribute
            val numValues = domainsIndexToValue[selectedAttribute].size
            node.decompositionAttribute = selectedAttribute
            node.children = ArrayList()
            var df: DataFrequencies
            for (j in 0 until numValues) {
                if (domainsIndexToValue[selectedAttribute][j] == null || domainsIndexToValue[selectedAttribute][j] == "?") continue
                val newNode = TreeNode()
                newNode.parent = node
                df = getSubset(node.data, selectedAttribute, j)
                newNode.data = df.data
                newNode.frequencyClasses = df.frequencyClasses
                newNode.decompositionValue = j
                node.children!!.add(newNode)
            }
            // Recursively divides children nodes
            when {
                isCrossValidation -> {
                    for (j in node.children!!.indices) {
                        decomposeNode(node.children!![j], selectedAttributesLocal, 0)
                    }
                }
                isRandomForest -> {
                    val rand = Random(mySeed)
                    var randomAttribute: Int
                    val numAtt = selectedAttributesLocal.size
                    for (j in node.children!!.indices) {
                        selectedAttributesLocal = ArrayList()
                        while (selectedAttributesLocal.size < numAtt) {
                            randomAttribute = rand.nextInt(numAttributes - 1)
                            if (!selectedAttributesLocal.contains(randomAttribute) && attributeTypes[randomAttribute] != AttributeType.Ignore)
                                selectedAttributesLocal.add(randomAttribute)
                        }
                        decomposeNode(node.children!![j], selectedAttributesLocal, mySeed + 1 + j)
                    }
                }
                else -> {
                    for (j in node.children!!.indices) {
                        decomposeNode(node.children!![j], selectedAttributesLocal, mySeed + 1 + j)
                    }
                }
            }
        }
        //If attribute is continuous
        else {
            node.decompositionAttribute = selectedAttribute
            node.children = ArrayList()
            var df: DataFrequencies
            //First less than threshold
            var newNode = TreeNode()
            newNode.parent = node
            val subsets = getSubsetsBelowAndAbove(node.data, selectedAttribute, bestCertainty.threshold)
            df = subsets.x
            newNode.data = df.data
            newNode.frequencyClasses = df.frequencyClasses
            newNode.decompositionValueContinuous = " <= " + bestCertainty.threshold
            newNode.decompositionValueContinuousSymbol = "<="
            newNode.thresholdContinuous = bestCertainty.threshold
            node.children!!.add(newNode)

            //Then over the threshold.
            newNode = TreeNode()
            newNode.parent = node
            df = subsets.y
            newNode.data = df.data
            newNode.frequencyClasses = df.frequencyClasses
            newNode.decompositionValueContinuous = " > " + bestCertainty.threshold
            newNode.decompositionValueContinuousSymbol = ">"
            newNode.thresholdContinuous = bestCertainty.threshold
            node.children!!.add(newNode)

            //Decompose children
            if (node.children!![0].data.isEmpty() || node.children!![1].data.isEmpty()) return
            when {
                isCrossValidation -> {
                    decomposeNode(node.children!![0], selectedAttributesLocal, 0)
                    decomposeNode(node.children!![1], selectedAttributesLocal, 0)
                }
                isRandomForest -> {
                    val rand = Random(mySeed)
                    var randomAttribute: Int
                    val numAtt = selectedAttributesLocal.size
                    selectedAttributesLocal = ArrayList()
                    while (selectedAttributesLocal.size < numAtt) {
                        randomAttribute = rand.nextInt(numAttributes - 1)
                        if (!selectedAttributesLocal.contains(randomAttribute) && attributeTypes[randomAttribute] != AttributeType.Ignore)
                            selectedAttributesLocal.add(randomAttribute)
                    }
                    decomposeNode(node.children!![0], selectedAttributesLocal, mySeed + 1)
                    selectedAttributesLocal = ArrayList()
                    while (selectedAttributesLocal.size < numAtt) {
                        randomAttribute = rand.nextInt(numAttributes - 1)
                        if (!selectedAttributesLocal.contains(randomAttribute) && attributeTypes[randomAttribute] != AttributeType.Ignore)
                            selectedAttributesLocal.add(randomAttribute)
                    }
                    decomposeNode(node.children!![1], selectedAttributesLocal, mySeed + 2)
                }
                else -> {
                    decomposeNode(node.children!![0], selectedAttributesLocal, mySeed + 1)
                    decomposeNode(node.children!![1], selectedAttributesLocal, mySeed + 2)
                }
            }
        }
    }

    fun imputeMissing() {
        for (attribute in 0 until numAttributes - 1) {
            if (attributeTypes[attribute] == AttributeType.Continuous) {
                if (domainsIndexToValue[attribute].containsValue("?")) {
                    //Find mean value
                    val mean = meanValues[attribute]
                    //Get index
                    val index = domainsValueToIndex[attribute]["?"]!!
                    //Replace missing with mean
                    domainsIndexToValue[attribute].replace(index, "?", mean)
                    domainsValueToIndex[attribute].remove("?")
                    domainsValueToIndex[attribute][mean] = index
                }
            } else if (attributeTypes[attribute] == AttributeType.Discrete) {
                if (domainsIndexToValue[attribute].containsValue("?")) {
                    //Find most common value
                    val mostCommonValue = mostCommonValues[attribute]
                    val mostCommonValueStr = domainsIndexToValue[attribute][mostCommonValue] as String
                    //Get index
                    val index = domainsValueToIndex[attribute]["?"]!!
                    //Replace missing with most common
                    domainsIndexToValue[attribute].replace(index, "?", mostCommonValueStr)
                    domainsValueToIndex[attribute].remove("?")
                    domainsValueToIndex[attribute][mostCommonValueStr] = index
                }
            }
        }
    }

    //Find the mean value of a continuous attribute
    private fun getMeanValue(attribute: Int): Double {
        var sum = 0.0
        var counter = 0
        for (point in trainData) {
            try {
                val attValue = domainsIndexToValue[attribute][point.attributes[attribute]] as Double
                sum += attValue
                counter++
            } catch (e: Exception) {
                //continue;
            }
        }
        return sum / counter
    }

    fun setMeanValues() {
        meanValues = DoubleArray(numAttributes - 1)
        for (i in 0 until numAttributes - 1) {
            if (attributeTypes[i] == AttributeType.Ignore) continue
            if (attributeTypes[i] == AttributeType.Continuous) {
                meanValues[i] = getMeanValue(i)
            } else meanValues[i] = 0.0
        }
    }

    //Find the most common values of a discrete attribute. This is needed for imputation.
    private fun getMostCommonValue(attribute: Int): Int {
        val frequencies = IntArray(domainsIndexToValue[attribute].size)
        for (point in trainData) {
            frequencies[point.attributes[attribute]]++
        }
        var mostFrequent = 0
        var index = 0
        for (i in frequencies.indices) {
            if (domainsIndexToValue[attribute][i] != "?") if (frequencies[i] > mostFrequent) {
                mostFrequent = frequencies[i]
                index = i
            }
        }
        return index
    }

    fun setMostCommonValues() {
        mostCommonValues = IntArray(numAttributes - 1)
        for (i in 0 until numAttributes - 1) {
            if (attributeTypes[i] == AttributeType.Ignore) continue
            if (attributeTypes[i] == AttributeType.Discrete) {
                mostCommonValues[i] = getMostCommonValue(i)
            } else mostCommonValues[i] = 0
        }
    }

    fun readTestData(filename: String) {
        //Read the test file
        val `in`: FileInputStream?
        val data = ArrayList<DataPoint>()
        try {
            val inputFile = File(filename)
            `in` = FileInputStream(inputFile)
        } catch (e: Exception) {
            System.err.println("Unable to open data file: $filename\n$e")
            exitProcess(1)
        }
        val bin = BufferedReader(InputStreamReader(`in`))
        var input: String?
        while (true) {
            try {
                input = bin.readLine()
            } catch (e: Exception) {
                System.err.println("Unable to read line from test file.")
                exitProcess(1)
            }
            if (input == null) {
                System.err.println("No data found in the data file: $filename\n")
                exitProcess(1)
            }
            if (input.startsWith("//")) continue
            if (input == "") continue
            break
        }
        var tokenizer: StringTokenizer
        while (input != null) {
            tokenizer = StringTokenizer(input, ",")
            val point = DataPoint(numAttributes)
            var next: String
            for (i in 0 until numAttributes) {
                next = tokenizer.nextToken().trim { it <= ' ' }
                if (attributeTypes[i] == AttributeType.Continuous) {
                    if (next == "?" || next == "NaN") point.attributes[i] = getSymbolValue(i, "?") else {
                        try {
                            point.attributes[i] = getSymbolValue(i, next.toDouble())
                        } catch (e: Exception) {
                            System.err.println("Error reading continuous value in test data.")
                            exitProcess(1)
                        }
                    }
                } else if (attributeTypes[i] == AttributeType.Discrete) {
                    point.attributes[i] = getSymbolValue(i, next)
                } else if (attributeTypes[i] == AttributeType.Ignore) {
                    point.attributes[i] = getSymbolValue(i, next)
                }
            }
            data.add(point)
            try {
                input = bin.readLine()
            } catch (e: Exception) {
                System.err.println("Unable to read line from test file.")
                exitProcess(1)
            }
        }
        try {
            bin.close()
        } catch (e: Exception) {
            System.err.println("Unable to close test file.")
            exitProcess(1)
        }
        testData = data

        //Resize root.frequencyClasses in case new class values were found in test dataset
        if (root.frequencyClasses.size < domainsIndexToValue[classAttribute].size) {
            val newArray = IntArray(domainsIndexToValue[classAttribute].size)
            System.arraycopy(root.frequencyClasses, 0, newArray, 0, root.frequencyClasses.size)
            root.frequencyClasses = newArray
        }
        print("Read data: " + testData.size + " cases for testing. ")
        print("\n")
    }

    /** Function to read the data file.
     * The first line of the data file should contain the names of all attributes.
     * The number of attributes is inferred from the number of words in this line.
     * The last word is taken as the name of the output attribute.
     * Each subsequent line contains the values of attributes for a data point.
     * If any line starts with // it is taken as a comment and ignored.
     * Blank lines are also ignored.
     */
    fun readData(filename: String) {
        val `in`: FileInputStream
        val data = ArrayList<DataPoint>()
        val numTraining: Int
        try {
            val inputFile = File(filename)
            `in` = FileInputStream(inputFile)
        } catch (e: Exception) {
            System.err.println("Unable to open data file: $filename\n")
            exitProcess(1)
        }
        val bin = BufferedReader(InputStreamReader(`in`))
        var input: String?
        var tokenizer: StringTokenizer

        //Read names file
        readNames(filename)
        try {
            input = bin.readLine()
        } catch (e: Exception) {
            System.err.println("Unable to read line from data file.")
            exitProcess(1)
        }
        while (input != null) {
            if (input.trim { it <= ' ' } == "") break
            if (input.startsWith("//")) continue
            if (input == "") continue
            tokenizer = StringTokenizer(input, ",")
            val numTokens = tokenizer.countTokens()
            if (numTokens != numAttributes) {
                System.err.println("Read " + data.size + " data")
                System.err.println("Last line read: $input")
                System.err.println("Expecting $numAttributes attributes")
                exitProcess(1)
            }

            val point = DataPoint(numAttributes)
            var next: String
            for (i in 0 until numAttributes) {
                next = tokenizer.nextToken().trim { it <= ' ' }
                if (attributeTypes[i] == AttributeType.Continuous) {
                    if (next == "?" || next == "NaN") point.attributes[i] = getSymbolValue(i, "?") else {
                        try {
                            point.attributes[i] = getSymbolValue(i, next.toDouble())
                        } catch (e: Exception) {
                            System.err.println("Error reading continuous value in train data.")
                            exitProcess(1)
                        }
                    }
                } else if (attributeTypes[i] == AttributeType.Discrete) {
                    point.attributes[i] = getSymbolValue(i, next)
                } else if (attributeTypes[i] == AttributeType.Ignore) {
                    point.attributes[i] = getSymbolValue(i, next)
                }
            }
            data.add(point)
            try {
                input = bin.readLine()
            } catch (e: Exception) {
                System.err.println("Unable to read line from data file.")
                exitProcess(1)
            }
        }
        try {
            bin.close()
        } catch (e: Exception) {
            System.err.println("Unable to close data file.")
            exitProcess(1)
        }
        val size = data.size
        root.frequencyClasses = IntArray(domainsIndexToValue[classAttribute].size)
        if (splitTrainData && !testDataExists && !isCrossValidation) {
            //Randomize the data
            data.shuffle()
            numTraining = size * 80 / 100
            for (i in 0 until size) {
                if (i < numTraining) {
                    val point = data[i]
                    root.data.add(point)
                    root.frequencyClasses[point.attributes[classAttribute]]++
                } else testData.add(data[i])
            }
        } else {
            for (point in data) {
                root.data.add(point)
                root.frequencyClasses[point.attributes[classAttribute]]++
            }
        }
        trainData = root.data
        if (splitTrainData && !testDataExists) {
            print("Read data: " + root.data.size + " cases for training.")
            print("\n")
            print("Read data: " + testData.size + " cases for testing.")
        } else print("Read data: " + root.data.size + " cases for training. ")
        print("\n")
    } // End of function readData

    private fun readNames(filename: String) {
        val `in`: FileInputStream?
        var input: String?
        val attributes = ArrayList<Tuple<String, String>>()
        //Read the names file
        try {
            `in` = if (filename.contains(".")) {
                val split = filename.split("\\.".toRegex()).toTypedArray()
                val inputFile = File(split[0] + ".names")
                FileInputStream(inputFile)
            } else {
                val inputFile = File("$fileName.names")
                FileInputStream(inputFile)
            }
        } catch (e: Exception) {
            System.err.println("Unable to open names file.")
            exitProcess(1)
        }
        val bin = BufferedReader(InputStreamReader(`in`))

        //Read first line containing class values.
        try {
            bin.readLine()
        } catch (e: Exception) {
            System.err.println("Unable to read line in names file.")
            exitProcess(1)
        }

        //Save attribute names and types to a tuple array.
        try {
            input = bin.readLine()
        } catch (e: Exception) {
            System.err.println("Unable to read line in names file.")
            exitProcess(1)
        }
        while (input != null) {
            if (!input.startsWith("|")) {
                val split = input.split(":".toRegex()).toTypedArray()
                if (split.size == 2) {
                    val t = Tuple(split[0].trim { it <= ' ' }, split[1].trim { it <= ' ' })
                    attributes.add(t)
                }
            }
            try {
                input = bin.readLine()
            } catch (e: Exception) {
                System.err.println("Unable to read line in names file.")
                exitProcess(1)
            }
        }

        //Set numAttributes. +1 for the class attribute
        numAttributes = attributes.size + 1

        //Set class attribute
        classAttribute = numAttributes - 1

        //Check for errors.
        if (numAttributes <= 1) {
            System.err.println("Expecting at least one input attribute and one output attribute")
            exitProcess(1)
        }

        //Initialize domains
        domainsIndexToValue = ArrayList()
        domainsValueToIndex = ArrayList()
        //domains = new ArrayList[numAttributes];
        for (i in 0 until numAttributes) {
            domainsIndexToValue.add(HashMap())
        }
        for (i in 0 until numAttributes) {
            domainsValueToIndex.add(HashMap())
        }

        //Set attributeNames. They should be in the same order as they appear in the data. +1 for the class
        attributeNames = arrayOfNulls(numAttributes)
        for (i in 0 until numAttributes - 1) {
            val t = attributes[i]
            attributeNames[i] = t.x
        }

        //Set the class. For now all class attribute names are the same: Class.
        attributeNames[numAttributes - 1] = "Class"

        //Initialize attributeTypes.
        attributeTypes = arrayOfNulls(numAttributes)

        //Set the attribute types.
        for (i in 0 until numAttributes - 1) {
            val attribute = attributes[i]
            when (attribute.y.trim { it <= ' ' }) {
                "continuous." -> attributeTypes[i] = AttributeType.Continuous
                "ignore." -> attributeTypes[i] = AttributeType.Ignore
                else -> attributeTypes[i] = AttributeType.Discrete
            }
        }

        //Set attribute type for the class.
        attributeTypes[numAttributes - 1] = AttributeType.Discrete
    }

    //-----------------------------------------------------------------------
    /*  This function counts the total nodes and the leaf nodes

    */
    private fun countNodes(node: TreeNode) {
        if (node.data.isNotEmpty()) totalNodes++
        if (node.children == null) {
            if (node.data.isNotEmpty()) totalRules++
            return
        }
        val numValues = node.children!!.size
        for (i in 0 until numValues) {
            countNodes(node.children!![i])
        }
    }

    private fun createFile() {
        val oos: ObjectOutputStream
        val fOut: FileOutputStream
        var fName = fileName
        fName = fName.substring(0, fName.length - 4)
        fName += "tree"
        try {
            //Check if file exists...delete it
            val inputFile = File(fName)
            if (inputFile.exists()) {
                val res = inputFile.delete()
                if (!res) {
                    print("Error deleting previous tree file.")
                    print("\n")
                    exitProcess(1)
                }
            }

            //Serialize and save to disk
            fOut = FileOutputStream(fName, false)
            val gz = GZIPOutputStream(fOut)
            oos = ObjectOutputStream(gz)
            oos.writeObject(this)
            oos.close()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun createFileRF() {
        val oos: ObjectOutputStream
        val fOut: FileOutputStream
        var fName = fileName
        fName = fName.substring(0, fName.length - 4)
        fName += "forest"
        try {
            //Check if file exists...delete it
            val inputFile = File(fName)
            val res = inputFile.delete()
            if (!res) {
                print("Error deleting previous random forest file.")
                print("\n")
                exitProcess(1)
            }

            //Serialize and save to disk
            fOut = FileOutputStream(fName, false)
            val gz = GZIPOutputStream(fOut)
            oos = ObjectOutputStream(gz)
            oos.writeObject(this)
            oos.close()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun deserializeFile(file: String): Cid3 {
        val ret: Cid3
        val objectInputStream: ObjectInputStream
        try {
            val `is` = GZIPInputStream(FileInputStream(file))
            objectInputStream = ObjectInputStream(`is`)
            ret = objectInputStream.readObject() as Cid3
            objectInputStream.close()
        } catch (e: Exception) {
            print("Error deserializing file.")
            print("\n")
            exitProcess(1)
        }
        return ret
    }

    fun createDecisionTree() {
        val start = Instant.now()
        val selectedAttributes = ArrayList<Int>()
        //Select ALL attributes
        for (i in 0 until numAttributes) {
            if (attributeTypes[i] == AttributeType.Ignore) continue
            selectedAttributes.add(i)
        }
        decomposeNode(root, selectedAttributes, 0)
        print("Decision tree created.")
        print("\n")
        countNodes(root)
        print("Rules:$totalRules")
        print("\n")
        print("Nodes:$totalNodes")
        print("\n")
        testDecisionTree()
        print("\n")
        val finish = Instant.now()
        val timeElapsed = Duration.between(start, finish)
        val timeElapsedString = formatDuration(timeElapsed)
        print("Time: $timeElapsedString")
        print("\n")
    }

    fun createCrossValidation() {
        val start = Instant.now()
        if (testDataExists) {
            trainData.addAll(testData)
            root.data = trainData
        }
        val chunkSize = root.data.size / 10
        val modulus = root.data.size % 10
        var counter = chunkSize
        var counterChunks = 0
        //Randomize the data
        root.data.shuffle()

        //Initialize chunks
        for (i in 0..9) {
            crossValidationChunks.add(ArrayList())
        }

        //First check if there is a remainder
        if (modulus != 0) {
            var i = 0
            while (i < root.data.size - modulus) {
                if (i < counter) {
                    crossValidationChunks[counterChunks].add(root.data[i])
                } else {
                    counter += chunkSize
                    counterChunks++
                    i--
                }
                i++
            }
            counter = 0
            for (j in root.data.size - modulus until root.data.size) {
                crossValidationChunks[counter].add(root.data[j])
                counter++
            }
        } else {
            var i = 0
            while (i < root.data.size) {
                if (i < counter) {
                    crossValidationChunks[counterChunks].add(root.data[i])
                } else {
                    counter += chunkSize
                    counterChunks++
                    i--
                }
                i++
            }
        }
        val threads = ArrayList<Thread>()
        for (i in 0..9) {
            val newRoot = TreeNode()
            val trainData = ArrayList<DataPoint>()
            for (k in 0..9) {
                if (k != i) trainData.addAll(crossValidationChunks[k])
            }
            newRoot.data = trainData
            newRoot.frequencyClasses = getFrequencies(newRoot.data)
            rootsCrossValidation.add(newRoot)

            //Create the cross-validation in parallel
            val selectedAttributes = ArrayList<Int>()
            //Select ALL attributes
            for (j in 0 until numAttributes - 1) {
                if (attributeTypes[j] == AttributeType.Ignore) continue
                selectedAttributes.add(j)
            }
            val thread = Thread { decomposeNode(rootsCrossValidation[i], selectedAttributes, 0) }
            threads.add(thread)
            thread.start()
        }
        while (threads.size > 0) {
            if (!threads[threads.size - 1].isAlive) threads.removeAt(threads.size - 1)
        }
        print("\n")
        print("10-fold cross-validation created with " + root.data.size + " cases.")
        print("\n")
        testCrossValidation()
        val finish = Instant.now()
        val timeElapsed = Duration.between(start, finish)
        val timeElapsedString = formatDuration(timeElapsed)
        print("\n")
        print("Time: $timeElapsedString")
        print("\n")
    }

    fun createCrossValidationRF() {
        val start = Instant.now()

        //Initialize array
        for (i in 0..9) {
            cvRandomForests.add(ArrayList())
        }
        if (testDataExists) {
            trainData.addAll(testData)
            root.data = trainData
        }
        val chunkSize = root.data.size / 10
        val modulus = root.data.size % 10
        var counter = chunkSize
        var counterChunks = 0
        //Randomize the data
        root.data.shuffle()

        //Initialize chunks
        for (i in 0..9) {
            crossValidationChunks.add(ArrayList())
        }
        //First check if there is a remainder
        if (modulus != 0) {
            var i = 0
            while (i < root.data.size - modulus) {
                if (i < counter) {
                    crossValidationChunks[counterChunks].add(root.data[i])
                } else {
                    counter += chunkSize
                    counterChunks++
                    i--
                }
                i++
            }
            counter = 0
            for (j in root.data.size - modulus until root.data.size) {
                crossValidationChunks[counter].add(root.data[j])
                counter++
            }
        } else {
            var i = 0
            while (i < root.data.size) {
                if (i < counter) {
                    crossValidationChunks[counterChunks].add(root.data[i])
                } else {
                    counter += chunkSize
                    counterChunks++
                    i--
                }
                i++
            }
        }

        //Create the 10 Random Forests
        for (i in 0..9) {
            val trainData = ArrayList<DataPoint>()
            for (k in 0..9) {
                if (k != i) trainData.addAll(crossValidationChunks[k])
            }
            createRandomForest(trainData, cvRandomForests[i], true)
        }
        print("\n")
        print("10-fold Random Forests cross-validation created with " + root.data.size + " cases.")
        print("\n")

        //Test the cross-validation
        testCrossValidationRF()
        val finish = Instant.now()
        val timeElapsed = Duration.between(start, finish)
        val timeElapsedString = formatDuration(timeElapsed)
        print("\n")
        print("Time: $timeElapsedString")
        print("\n")
    }

    fun createRandomForest(data: ArrayList<DataPoint>, roots: ArrayList<TreeNode>, cv: Boolean) {
        val start = Instant.now()
        var numberOfAttributes = 0
        for (i in 0 until numAttributes - 1) {
            if (attributeTypes[i] != AttributeType.Ignore) numberOfAttributes++
        }
        val numAttributesForRandomForest = ln(numberOfAttributes + 1.toDouble()) / ln(2.0)
        val numAttributesForRandomForestInt = numAttributesForRandomForest.toInt()
        var randomAttribute: Int
        var selectedAttributes: ArrayList<Int>
        val threads = ArrayList<Thread>()
        val rand = Random(seed)
        for (i in 0 until numberOfTrees) {
            selectedAttributes = ArrayList()
            while (selectedAttributes.size < numAttributesForRandomForestInt) {
                randomAttribute = rand.nextInt(numAttributes - 1)
                if (!selectedAttributes.contains(randomAttribute) && attributeTypes[randomAttribute] != AttributeType.Ignore) selectedAttributes.add(randomAttribute)
            }
            val cloneRoot = TreeNode()
            cloneRoot.data = data
            cloneRoot.frequencyClasses = getFrequencies(data)
            roots.add(cloneRoot)

            //Create the Random Forest in parallel
            val selectedAttributes2 = selectedAttributes
            val thread = Thread { decomposeNode(roots[i], selectedAttributes2, seed + 1 + i) }
            threads.add(thread)
            thread.start()
        }
        while (threads.size > 0) {
            if (!threads[threads.size - 1].isAlive) threads.removeAt(threads.size - 1)
        }
        if (!cv) {
            print("\n")
            print("Random Forest of " + roots.size + " trees created.")
            print("\n")
            print("\n")
            testRandomForest()
            val finish = Instant.now()
            val timeElapsed = Duration.between(start, finish)
            val timeElapsedString = formatDuration(timeElapsed)
            print("\n")
            print("\n")
            print("Time: $timeElapsedString")
            print("\n")
        }
    }

    private fun testExamplePoint(example: DataPoint, node: TreeNode): TreeNode {
        var nodeLocal = node
        val splitAttribute: Int
        val attributeValue: Int
        val attributeRealValue: Double
        splitAttribute = nodeLocal.decompositionAttribute
        attributeValue = example.attributes[splitAttribute]
        if (nodeLocal.children == null || nodeLocal.children!!.isEmpty()) return nodeLocal

        //Check if attribute is discrete
        if (attributeTypes[splitAttribute] == AttributeType.Discrete) {
            for (i in nodeLocal.children!!.indices) {
                if (nodeLocal.children!![i].decompositionValue == attributeValue) {
                    nodeLocal = nodeLocal.children!![i]
                    break
                }
            }
        }
        //Check if attribute is continuous
        if (attributeTypes[splitAttribute] == AttributeType.Continuous) {
            attributeRealValue = domainsIndexToValue[splitAttribute][example.attributes[splitAttribute]] as Double
            nodeLocal = if (attributeRealValue <= nodeLocal.children!![0].thresholdContinuous) {
                nodeLocal.children!![0]
            } else nodeLocal.children!![1]
        }
        return testExamplePoint(example, nodeLocal)
    }

    private fun testExample(example: DataPoint): Boolean {
        val node: TreeNode = testExamplePoint(example, root)
        return if (node.data.isEmpty()) {
            example.attributes[classAttribute] == getMostCommonClass(node.parent)
        } else {
            example.attributes[classAttribute] == getMostCommonClass(node)
        }
    }

    private fun testExampleCV(example: DataPoint, tree: TreeNode): Boolean {
        val node: TreeNode = testExamplePoint(example, tree)
        return if (node.data.isEmpty()) {
            example.attributes[classAttribute] == getMostCommonClass(node.parent)
        } else {
            example.attributes[classAttribute] == getMostCommonClass(node)
        }
    }

    private fun testExampleRF(example: DataPoint, roots: ArrayList<TreeNode>): Boolean {
        var node: TreeNode
        var isTrue = 0
        var isFalse = 0
        val results = ArrayList<Boolean>()
        for (treeNode in roots) {
            node = testExamplePoint(example, treeNode)
            if (node.data.isEmpty()) {
                if (example.attributes[classAttribute] == getMostCommonClass(node.parent)) results.add(true) else results.add(false)
            } else {
                if (example.attributes[classAttribute] == getMostCommonClass(node)) results.add(true) else results.add(false)
            }
        }
        //Voting now
        for (result in results) {
            if (result) isTrue++ else isFalse++
        }
        return isTrue > isFalse
    }

    private fun testDecisionTree() {
        var testErrors = 0
        var testCorrects = 0
        for (point in testData) {
            if (testExample(point)) testCorrects++ else testErrors++
        }
        var trainErrors = 0
        var trainCorrects = 0
        for (point in trainData) {
            if (testExample(point)) trainCorrects++ else trainErrors++
        }
        print("\n")
        print("TRAIN DATA: ")
        print("\n")
        print("=================================")
        print("\n")
        print("Correct guesses: $trainCorrects")
        print("\n")
        val rounded = (1.0 * trainErrors * 100 / trainData.size * 10).roundToInt() / 10.0
        print("Incorrect guesses: $trainErrors ($rounded%)")
        print("\n")
        if (testData.isNotEmpty()) {
            print("\n")
            print("TEST DATA: ")
            print("\n")
            print("=================================")
            print("\n")
            print("Correct guesses: $testCorrects")
            print("\n")
            val rounded1 = (1.0 * testErrors * 100 / testData.size * 10).roundToInt() / 10.0
            print("Incorrect guesses: $testErrors ($rounded1%)")
        }
        print("\n")
        print("\n")
        print("Root: " + attributeNames[root.decompositionAttribute])
        print("\n")
    }

    private fun testCrossValidation() {
        var testErrors: Int
        val meanErrors: Double
        var percentageErrors = 0.0
        val errorsFoldK = DoubleArray(10)
        for (i in 0..9) {
            testErrors = 0
            val currentTree = rootsCrossValidation[i]
            val currentTest = crossValidationChunks[i]
            for (point in currentTest) {
                if (!testExampleCV(point, currentTree)) testErrors++
            }
            percentageErrors += 1.0 * testErrors / currentTest.size * 100
            val rounded1 = (1.0 * testErrors / currentTest.size * 100 * 10).roundToInt() / 10.0
            print("\n")
            if (i != 9) {
                print("Fold#  " + (i + 1) + " Errors: " + rounded1 + "%")
            }
            else{
                print("Fold# " + (i + 1) + " Errors: " + rounded1 + "%")
            }
            //Save k errors for SE
            errorsFoldK[i] = 1.0 * testErrors / currentTest.size * 100
        }
        meanErrors = percentageErrors / 10
        val rounded1 = (meanErrors * 10).roundToInt() / 10.0
        print("\n")
        print("\n")
        print("Mean errors: $rounded1%")

        //Calculate average
        var meanFolds = 0.0
        for (i in 0..9) {
            meanFolds += errorsFoldK[i]
        }
        meanFolds = 1.0 * meanFolds / 10

        //Calculate SE (Standard Errors)
        var sumMeanSE = 0.0
        for (i in 0..9) {
            sumMeanSE += (1.0 * errorsFoldK[i] - meanFolds) * (1.0 * errorsFoldK[i] - meanFolds)
        }
        sumMeanSE = sqrt(sumMeanSE / 10)
        val se = sumMeanSE / sqrt(10.0)
        val roundedSE = (se * 10).roundToInt() / 10.0
        print("\n")
        print("SE: $roundedSE%")
        print("\n")
    }

    private fun testCrossValidationRF() {
        var sum = 0.0
        val errorsFoldK = DoubleArray(10)
        var current: Double
        //For each Random Forest
        for (i in 0..9) {
            val currentForest = cvRandomForests[i]
            val currentTestData = crossValidationChunks[i]
            current = testRandomForest(currentTestData, currentForest, i + 1)
            sum += current
            //Save k errors for SE
            errorsFoldK[i] = current
        }
        val meanErrors = sum / 10
        val rounded1 = (meanErrors * 10).roundToInt() / 10.0
        print("\n")
        print("\n")
        print("Mean errors: $rounded1%")
        print("\n")

        //Calculate SE (Standard Errors)
        var sumMeanSE = 0.0
        for (i in 0..9) {
            sumMeanSE += (1.0 * errorsFoldK[i] - meanErrors) * (1.0 * errorsFoldK[i] - meanErrors)
        }
        sumMeanSE = sqrt(sumMeanSE / 10)
        val se = sumMeanSE / sqrt(10.0)
        val roundedSE = (se * 10).roundToInt() / 10.0
        print("SE: $roundedSE%")
        print("\n")
    }

    //This overload method is intended to be used when Random Forest cross-validation is selected.
    private fun testRandomForest(testD: ArrayList<DataPoint>, roots: ArrayList<TreeNode>, index: Int): Double {
        var testErrors = 0
        val testSize = testD.size
        for (point in testD) {
            if (!testExampleRF(point, roots)) testErrors++
        }
        print("\n")
        val rounded1 = (1.0 * testErrors * 100 / testSize * 10).roundToInt() / 10.0
        if (index != 10)
            print("Fold#  $index Errors: $rounded1%")
        else print("Fold# $index Errors: $rounded1%")
        return rounded1
    }

    private fun testRandomForest() {
        var testErrors = 0
        var testCorrects = 0
        for (point in testData) {
            if (testExampleRF(point, rootsRandomForest)) testCorrects++ else testErrors++
        }
        var trainErrors = 0
        var trainCorrects = 0
        for (point in trainData) {
            if (testExampleRF(point, rootsRandomForest)) trainCorrects++ else trainErrors++
        }
        print("TRAIN DATA: ")
        print("\n")
        print("=================================")
        print("\n")
        print("Correct guesses: $trainCorrects")
        print("\n")
        val rounded = (1.0 * trainErrors * 100 / trainData.size * 10).roundToInt() / 10.0
        print("Incorrect guesses: $trainErrors ($rounded%)")
        print("\n")
        if (testData.isNotEmpty()) {
            print("\n")
            print("TEST DATA: ")
            print("\n")
            print("=================================")
            print("\n")
            print("Correct guesses: $testCorrects")
            print("\n")
            val rounded1 = (1.0 * testErrors * 100 / testData.size * 10).roundToInt() / 10.0
            print("Incorrect guesses: $testErrors ($rounded1%)")
        }
    }

    fun queryTree(file: String) {
        var fileLocal = file
        if (!fileLocal.endsWith(".tree")) fileLocal += ".tree"
        val inputFile = File(fileLocal)
        val id3: Cid3?
        if (inputFile.exists()) {
            val `in` = Scanner(System.`in`)
            id3 = deserializeFile(fileLocal)
            print("\n")
            print("Tree file deserialized.")
            print("\n")
            var currentNode = id3.root
            var attributeValue = 0
            while (!(currentNode.children == null || currentNode.children!!.isEmpty())) {
                //If attribute is discrete, show all possible values for it
                if (id3.attributeTypes[currentNode.decompositionAttribute] == AttributeType.Discrete) {
                    print("Please enter attribute: " + id3.attributeNames[currentNode.decompositionAttribute])
                    print("\n")
                    print("(possible values are: ")
                    var values = StringBuilder()
                    val valuesArray = ArrayList<String>()
                    for (i in 0 until id3.domainsIndexToValue[currentNode.decompositionAttribute].size) {
                        valuesArray.add(id3.domainsIndexToValue[currentNode.decompositionAttribute][i] as String)
                    }
                    valuesArray.sort()
                    for (value in valuesArray) {
                        values.append(value)
                        values.append(", ")
                    }
                    values = StringBuilder("?, " + values.substring(0, values.length - 2))
                    print("$values)")
                    print("\n")
                    while (true) {
                        try {
                            val s = `in`.nextLine()
                            if (s == "?") {
                                attributeValue = id3.mostCommonValues[currentNode.decompositionAttribute]
                                break
                            } else attributeValue = id3.domainsValueToIndex[currentNode.decompositionAttribute][s]!!
                            break
                        } catch (e: Exception) {
                            println("Please enter a valid value:")
                        }
                    }
                }
                for (i in currentNode.children!!.indices) {
                    //Check if attribute is continuous
                    if (id3.attributeTypes[currentNode.decompositionAttribute] == AttributeType.Continuous) {
                        if (currentNode.children!![i].decompositionValueContinuousSymbol == "<=") {
                            print("Is attribute: " + id3.attributeNames[currentNode.decompositionAttribute] + " <= " + currentNode.children!![i].thresholdContinuous + " ? (y/n/?)")
                            print("\n")
                            val s = `in`.nextLine()
                            if (s == "y" || s == "Y" || s == "yes" || s == "Yes" || s == "YES" || s == "n" || s == "N" || s == "no" || s == "No" || s == "NO" || s == "?") {
                                if (s == "y" || s == "Y" || s == "yes" || s == "Yes" || s == "YES") {
                                    currentNode = currentNode.children!![i]
                                    break
                                } else if (s == "?") {
                                    val mean = id3.meanValues[currentNode.decompositionAttribute]
                                    currentNode = if (mean <= currentNode.children!![i].thresholdContinuous) {
                                        currentNode.children!![i]
                                    } else {
                                        currentNode.children!![i + 1]
                                    }
                                    break
                                } else {
                                    currentNode = currentNode.children!![i + 1]
                                    break
                                }
                            } else {
                                print("\n")
                                print("Error: wrong input value")
                                print("\n")
                                exitProcess(1)
                            }
                        }
                    } else if (currentNode.children!![i].decompositionValue == attributeValue) {
                        currentNode = currentNode.children!![i]
                        break
                    }
                }
            }
            //Check if the node is empty, if so, return its parent most frequent class.
            var isEmpty = true
            for (i in currentNode.frequencyClasses.indices) {
                if (currentNode.frequencyClasses[i] != 0) {
                    isEmpty = false
                    break
                }
            }
            val mostCommon: Int
            mostCommon = if (isEmpty) id3.getMostCommonClass(currentNode.parent) else id3.getMostCommonClass(currentNode)
            val mostCommonStr = id3.domainsIndexToValue[id3.classAttribute][mostCommon] as String?
            //Print class attribute value
            println("Class attribute value is: $mostCommonStr")
        } else println("The file doesn't exist.")
    }

    fun queryTreeOutput(treeFile: String, casesFile: String) {
        var treeFileLocal = treeFile
        var casesFileLocal = casesFile
        if (!treeFileLocal.endsWith(".tree")) treeFileLocal += ".tree"
        val fileOutStr: String
        fileOutStr = if (!casesFileLocal.endsWith(".cases")) "$casesFileLocal.tmp" else {
            casesFileLocal.substring(0, casesFileLocal.length - 5) + "tmp"
        }
        val inputTreeFile = File(treeFileLocal)
        val id3: Cid3?
        val fileOut: FileWriter?
        try {
            fileOut = FileWriter(fileOutStr, false)
        }
        catch (e: Exception) {
            System.err.println("Error creating temporal file.")
            exitProcess(1)
        }
        val fileBuf = BufferedWriter(fileOut)
        val printOut = PrintWriter(fileBuf)
        if (inputTreeFile.exists()) {
            id3 = deserializeFile(treeFileLocal)
            print("\n")
            print("Tree file deserialized.")
            print("\n")
            val inCases: FileInputStream?
            try {
                if (!casesFileLocal.endsWith(".cases")) casesFileLocal += ".cases"
                val inputFile = File(casesFileLocal)
                inCases = FileInputStream(inputFile)
            }
            catch (e: Exception) {
                System.err.println("Unable to open cases file.")
                exitProcess(1)
            }
            val bin = BufferedReader(InputStreamReader(inCases))
            var input: String?
            var tokenizer: StringTokenizer
            try {
                input = bin.readLine()
            }
            catch (e: Exception) {
                System.err.println("Unable to read line:")
                exitProcess(1)
            }
            while (input != null) {
                if (input.trim { it <= ' ' } == "") continue
                if (input.startsWith("//")) continue
                tokenizer = StringTokenizer(input, ",")
                val numTokens = tokenizer.countTokens()
                if (numTokens != id3.numAttributes - 1) {
                    System.err.println("Expecting " + (id3.numAttributes - 1) + " attributes")
                    exitProcess(1)
                }
                val point = DataPoint(id3.numAttributes)
                var next: String
                for (i in 0 until id3.numAttributes - 1) {
                    next = tokenizer.nextToken().trim { it <= ' ' }
                    if (id3.attributeTypes[i] == AttributeType.Continuous) {
                        if (next == "?" || next == "NaN") {
                            val value: Double = id3.meanValues[i]
                            point.attributes[i] = id3.getSymbolValue(i, value)
                        } else {
                            try {
                                point.attributes[i] = id3.getSymbolValue(i, next.toDouble())
                            } catch (e: Exception) {
                                System.err.println("Error reading continuous value in train data.")
                                exitProcess(1)
                            }
                        }
                    } else if (id3.attributeTypes[i] == AttributeType.Discrete) {
                        if (next == "?" || next == "NaN") {
                            point.attributes[i] = id3.mostCommonValues[i]
                        } else point.attributes[i] = id3.getSymbolValue(i, next)
                    }
                }
                //Test the example point
                var node: TreeNode
                node = id3.testExamplePoint(point, id3.root)
                var isEmpty = true
                var caseClass: Int
                //Check if the node is empty, if so, return its parent most frequent class.
                for (j in node.frequencyClasses.indices) {
                    if (node.frequencyClasses[j] != 0) {
                        isEmpty = false
                        break
                    }
                }
                //If node is empty
                caseClass = if (isEmpty) id3.getMostCommonClass(node.parent) else id3.getMostCommonClass(node)

                //Print line to output tmp file
                val classValue = id3.domainsIndexToValue[id3.classAttribute][caseClass] as String?
                val line = "$input,$classValue"
                printOut.write(line)
                printOut.println()

                //continue the loop
                try {
                    input = bin.readLine()
                }
                catch (e: Exception) {
                    System.err.println("Unable to read line.")
                    exitProcess(1)
                }
            }
            printOut.close()
            print("\n")
            print("Results saved to tmp file.")
            print("\n")
        } else println("The tree file doesn't exist.")
    }

    fun queryRandomForestOutput(rfFile: String, casesFile: String) {
        var rfFileLocal = rfFile
        var casesFileLocal = casesFile
        if (!rfFileLocal.endsWith(".forest")) rfFileLocal += ".forest"
        val fileOutStr: String
        fileOutStr = if (!casesFileLocal.endsWith(".cases")) "$casesFileLocal.tmp" else {
            casesFileLocal.substring(0, casesFileLocal.length - 5) + "tmp"
        }
        val inputForestFile = File(rfFileLocal)
        val id3: Cid3?
        val fileOut: FileWriter?
        try {
            fileOut = FileWriter(fileOutStr, false)
        } catch (e: Exception) {
            System.err.println("Error creating temporal file.")
            exitProcess(1)
        }
        val fileBuf = BufferedWriter(fileOut)
        val printOut = PrintWriter(fileBuf)
        if (inputForestFile.exists()) {
            id3 = deserializeFile(rfFileLocal)
            print("\n")
            print("Forest file deserialized.")
            print("\n")
            val inCases: FileInputStream?
            try {
                if (!casesFileLocal.endsWith(".cases")) casesFileLocal += ".cases"
                val inputFile = File(casesFileLocal)
                inCases = FileInputStream(inputFile)
            } catch (e: Exception) {
                System.err.println("Unable to open cases file.")
                exitProcess(1)
            }
            val bin = BufferedReader(InputStreamReader(inCases))
            var input: String?
            var tokenizer: StringTokenizer
            try {
                input = bin.readLine()
            }
            catch (e: Exception) {
                System.err.println("Unable to read line:")
                exitProcess(1)
            }
            while (input != null) {
                if (input.trim { it <= ' ' } == "") continue
                if (input.startsWith("//")) continue
                tokenizer = StringTokenizer(input, ",")
                val numTokens = tokenizer.countTokens()
                if (numTokens != id3.numAttributes - 1) {
                    System.err.println("Expecting " + (id3.numAttributes - 1) + " attributes")
                    exitProcess(1)
                }
                val point = DataPoint(id3.numAttributes)
                var next: String
                for (i in 0 until id3.numAttributes - 1) {
                    next = tokenizer.nextToken().trim { it <= ' ' }
                    if (id3.attributeTypes[i] == AttributeType.Continuous) {
                        if (next == "?" || next == "NaN") {
                            val value: Double = id3.meanValues[i]
                            point.attributes[i] = id3.getSymbolValue(i, value)
                        } else {
                            try {
                                point.attributes[i] = id3.getSymbolValue(i, next.toDouble())
                            } catch (e: Exception) {
                                System.err.println("Error reading continuous value in train data.")
                                exitProcess(1)
                            }
                        }
                    } else if (id3.attributeTypes[i] == AttributeType.Discrete) {
                        if (next == "?" || next == "NaN") {
                            point.attributes[i] = id3.mostCommonValues[i]
                        } else point.attributes[i] = id3.getSymbolValue(i, next)
                    }
                }
                //Check the created example against the random forest
                val classAttrValues = IntArray(id3.domainsIndexToValue[id3.classAttribute].size)
                val roots = id3.rootsRandomForest
                var node: TreeNode
                var resultClass = 0
                for (treeNode in roots) {
                    node = id3.testExamplePoint(point, treeNode)
                    //Check if the node is empty, if so, return its parent most frequent class.
                    var isEmpty = true
                    for (j in node.frequencyClasses.indices) {
                        if (node.frequencyClasses[j] != 0) {
                            isEmpty = false
                            break
                        }
                    }
                    //If node is empty
                    if (isEmpty) classAttrValues[id3.getMostCommonClass(node.parent)]++ else classAttrValues[id3.getMostCommonClass(node)]++
                }
                //Voting now
                for (i in 1 until classAttrValues.size) {
                    if (classAttrValues[i] > resultClass) resultClass = i
                }
                //Print line to output tmp file
                val classValue = id3.domainsIndexToValue[id3.classAttribute][resultClass] as String?
                val line = "$input,$classValue"
                printOut.write(line)
                printOut.println()

                //continue the loop
                try {
                    input = bin.readLine()
                } catch (e: Exception) {
                    System.err.println("Unable to read line.")
                    exitProcess(1)
                }
            }
            printOut.close()
            print("\n")
            print("Results saved to tmp file.")
            print("\n")
        } else println("The forest file doesn't exist.")
    }

    fun queryRandomForest(file: String) {
        var fileLocal = file
        if (!fileLocal.endsWith(".forest")) fileLocal += ".forest"
        val inputFile = File(fileLocal)
        val `in` = Scanner(System.`in`)
        val id3: Cid3?
        if (inputFile.exists()) {
            id3 = deserializeFile(fileLocal)
            print("\n")
            print("Random Forest file deserialized.")
            print("\n")
            val example = DataPoint(id3.numAttributes)
            //Enter all attributes into an example except the class, that's why -1
            for (i in 0 until id3.numAttributes - 1) {
                if (id3.attributeTypes[i] == AttributeType.Ignore) continue
                if (id3.attributeTypes[i] == AttributeType.Discrete) {
                    print("\n")
                    print("Please enter attribute: " + id3.attributeNames[i])
                    print("\n")
                    print("(possible values are: ")
                    var values = StringBuilder()
                    val valuesArray = ArrayList<String>()
                    for (j in 0 until id3.domainsIndexToValue[i].size) {
                        valuesArray.add(id3.domainsIndexToValue[i][j] as String)
                    }
                    valuesArray.sort()
                    for (item in valuesArray) {
                        values.append(item)
                        values.append(", ")
                    }
                    values = StringBuilder("?, " + values.substring(0, values.length - 2))
                    print("$values)")
                    print("\n")
                    while (true) {
                        var value: Int
                        val s = `in`.nextLine()
                        if (s == "?") {
                            value = id3.mostCommonValues[i]
                            example.attributes[i] = value
                            break
                        } else if (id3.domainsIndexToValue[i].containsValue(s)) {
                            example.attributes[i] = id3.getSymbolValue(i, s)
                            break
                        } else println("Please enter a valid value:")
                    }
                } else {
                    print("\n")
                    print("Please enter attribute: " + id3.attributeNames[i])
                    print("\n")
                    while (true) {
                        val s = `in`.nextLine()
                        try {
                            val value: Double = if (s == "?") id3.meanValues[i] else s.toDouble()
                            example.attributes[i] = id3.getSymbolValue(i, value)
                            break
                        } catch (e: Exception) {
                            println("Please enter a valid value:")
                        }
                    }
                }
            }

            //Check the created example against the random forest
            val classAttrValues = IntArray(id3.domainsIndexToValue[id3.classAttribute].size)
            val roots = id3.rootsRandomForest
            var node: TreeNode
            var resultClass = 0
            for (treeNode in roots) {
                node = id3.testExamplePoint(example, treeNode)
                //Check if the node is empty, if so, return its parent most frequent class.
                var isEmpty = true
                for (j in node.frequencyClasses.indices) {
                    if (node.frequencyClasses[j] != 0) {
                        isEmpty = false
                        break
                    }
                }
                //If node is empty
                if (isEmpty) classAttrValues[id3.getMostCommonClass(node.parent)]++ else classAttrValues[id3.getMostCommonClass(node)]++
            }
            //Voting now
            for (i in 1 until classAttrValues.size) {
                if (classAttrValues[i] > resultClass) resultClass = i
            }
            //Print the answer
            val mostCommonStr = id3.domainsIndexToValue[id3.classAttribute][resultClass] as String?
            print("\n")
            println("Class attribute value is: $mostCommonStr")
        } else println("The file doesn't exist.")
    }

    companion object {
        fun formatDuration(duration: Duration): String {
            val seconds = duration.seconds
            val absSeconds = abs(seconds)
            val positive = String.format(
                    "%d:%02d:%02d",
                    absSeconds / 3600,
                    absSeconds % 3600 / 60,
                    absSeconds % 60)
            return if (seconds < 0) "-$positive" else positive
        }

        /* Here is the definition of the main function */
        @JvmStatic
        fun main(args: Array<String>) {
            val me = Cid3()

            //Initialize values
            me.isCrossValidation = false
            me.testDataExists = false
            me.isRandomForest = false
            val options = Options()
            val help = Option("h", "help", false, "print this message")
            help.isRequired = false
            options.addOption(help)
            val save = Option("s", "save", false, "save tree/random forest")
            save.isRequired = false
            options.addOption(save)
            val partition = Option("p", "partition", false, "partition train/test data")
            save.isRequired = false
            options.addOption(partition)
            val criteria = Option("c", "criteria", true, "input criteria: c[Certainty], e[Entropy], g[Gini]")
            criteria.isRequired = false
            options.addOption(criteria)
            val file = Option("f", "file", true, "input file")
            file.isRequired = true
            options.addOption(file)
            val crossValidation = Option("v", "validation", false, "create 10-fold cross-validation")
            crossValidation.isRequired = false
            options.addOption(crossValidation)
            val randomForest = Option("r", "forest", true, "create random forest, enter # of trees")
            randomForest.isRequired = false
            options.addOption(randomForest)
            val query = Option("q", "query", true, "query model, enter: t[Tree] or r[Random forest]")
            query.isRequired = false
            options.addOption(query)
            val output = Option("o", "output", true, "output file")
            query.isRequired = false
            options.addOption(output)

            //Declare parser and formatter
            val parser: CommandLineParser = DefaultParser()
            val formatter = HelpFormatter()
            val cmd: CommandLine
            try {
                for (arg in args) {
                    if (arg.contains(" -h ") || arg.contains(" --help ")) {
                        //Print help message
                        formatter.printHelp("java -jar cid3.jar", options)
                        exitProcess(1)
                    }
                }
                cmd = parser.parse(options, args)
            } catch (e: ParseException) {
                println(e.message)
                formatter.printHelp("java -jar cid3.jar", options)
                exitProcess(1)
            }

            //Set criteria
            if (cmd.hasOption("criteria")) {
                when (cmd.getOptionValue("criteria")) {
                    "C", "c" -> me.criteria = Criteria.Certainty
                    "G", "g" -> me.criteria = Criteria.Gini
                    "E", "e" -> me.criteria = Criteria.Entropy
                    else -> {
                        formatter.printHelp("java -jar cid3.jar", options)
                        exitProcess(1)
                    }
                }
            }

            //Set file path
            var inputFilePath = cmd.getOptionValue("file")
            val originalInputFilePath = inputFilePath

            //Set validation
            me.isCrossValidation = cmd.hasOption("validation")

            //Set split data
            me.splitTrainData = cmd.hasOption("partition")

            //Set Random Forest
            if (cmd.hasOption("forest")) {
                val numberOfTrees = cmd.getOptionValue("forest")
                try {
                    me.numberOfTrees = numberOfTrees.toInt()
                } catch (e: Exception) {
                    print("Error: Incorrect number of trees")
                    print("\n")
                    exitProcess(1)
                }
                me.isRandomForest = true
            } else me.isRandomForest = false

            //Print help message
            if (cmd.hasOption("help")) {
                formatter.printHelp("java -jar cid3.jar", options)
                exitProcess(1)
            }

            //Show application title
            print("\n")
            val dayInMonth = LocalDateTime.now().dayOfMonth
            var day = LocalDateTime.now().dayOfWeek.name.toLowerCase()
            day = day.substring(0, 1).toUpperCase() + day.substring(1)
            var month = LocalDateTime.now().month.name.toLowerCase()
            month = month.substring(0, 1).toUpperCase() + month.substring(1)
            val time = DateTimeFormatter.ofPattern("hh:mm:ss a")
            val timeString = LocalDateTime.now().format(time)
            print("CID3 [Version 1.0]              $day $month $dayInMonth $timeString")
            print("\n")
            print("------------------")
            print("\n")

            //if is a query, deserialize file and query model
            val outputFilePath: String
            if (cmd.hasOption("query")) {
                val model = cmd.getOptionValue("query")
                if (model == "t") {
                    if (cmd.hasOption("output")) {
                        outputFilePath = cmd.getOptionValue("output")
                        me.queryTreeOutput(originalInputFilePath, outputFilePath)
                    } else me.queryTree(originalInputFilePath)
                } else if (model == "r") {
                    if (cmd.hasOption("output")) {
                        outputFilePath = cmd.getOptionValue("output")
                        me.queryRandomForestOutput(originalInputFilePath, outputFilePath)
                    } else me.queryRandomForest(originalInputFilePath)
                }
            }
            else {
                //Check if test data exists
                if (!inputFilePath.endsWith(".data")) inputFilePath += ".data"
                var nameTestData = inputFilePath.substring(0, inputFilePath.length - 4)
                nameTestData += "test"
                val inputFile = File(nameTestData)
                me.testDataExists = inputFile.exists()

                //Read data
                me.readData(inputFilePath)
                //Set global variable
                me.fileName = inputFilePath
                //Read test data
                if (me.testDataExists) me.readTestData(nameTestData)

                //Create a Tree or a Random Forest for saving to disk
                if (cmd.hasOption("save")) {
                    me.trainData.addAll(me.testData)
                    me.root.data = me.trainData
                    me.testData.clear()
                    me.setMeanValues()
                    me.setMostCommonValues()
                    me.imputeMissing()
                    if (me.isRandomForest) {
                        me.createRandomForest(me.root.data, me.rootsRandomForest, false)
                        print("\n")
                        print("Saving random forest...")
                        me.createFileRF()
                        print("\n")
                        print("Random forest saved to disk.")
                    } else {
                        me.createDecisionTree()
                        print("\n")
                        print("Saving tree...")
                        me.createFile()
                        print("\n")
                        print("Tree saved to disk.")
                    }
                    print("\n")
                } //Create CV, RF, Tree without saving
                else {
                    me.setMeanValues()
                    me.setMostCommonValues()
                    me.imputeMissing()
                    if (me.isCrossValidation && me.isRandomForest) me.createCrossValidationRF()
                    else if (me.isCrossValidation) me.createCrossValidation()
                    else if (me.isRandomForest) me.createRandomForest(me.root.data, me.rootsRandomForest, false)
                    else me.createDecisionTree()
                }
            }
        }
    }
}