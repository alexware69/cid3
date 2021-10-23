# CID3

This is a full-featured Decision Trees and Random Forests generator. It can save trees or forests to disk for later use. It is possible to query trees and Random Forests and to fill out an unlabeled file with the predicted classes. Documentation is not yet available, although the program options can be shown with command:   

```
% java -jar cid3.jar -h

usage: java -jar cid3.jar
 -c,--criteria <arg>   input criteria: c[Certainty], e[Entropy], g[Gini]
 -f,--file <arg>       input file
 -h,--help             print this message
 -o,--output <arg>     output file
 -p,--partition        partition train/test data
 -q,--query <arg>      query model, enter: t[Tree] or r[Random forest]
 -r,--forest <arg>     create random forest, enter # of trees
 -s,--save             save tree/random forest
 -t,--threads <arg>    maximum number of threads (default is 500)
 -v,--validation       create 10-fold cross-validation
 -ver,--version        version
```

Example run with titanic dataset:
```
user@User-MBP datasets % java -jar cid3.jar -f titanic

CID3 [Version 1.0.3]              Saturday October 23, 2021 12:09:35 PM
------------------
[ ✓ ] Read data: 891 cases for training. (10 attributes)
[ ✓ ] Decision tree created.

Rules:276
Nodes:514

Importance Cause   Attribute Name
---------- -----   --------------
      0.57   yes ············ Sex
      0.36   yes ········· Pclass
      0.30   yes ··········· Fare
      0.28   yes ······· Embarked
      0.27   yes ·········· SibSp
      0.26   yes ·········· Parch
      0.23    no ············ Age


[==== TRAIN DATA ====] 

Correct guesses: 875
Incorrect guesses: 16 (1.8%)

# Of Cases  False Pos  False Neg   Class
----------  ---------  ---------   -----
       549         14          2 ····· 0
       342          2         14 ····· 1

Time: 0:00:00
```

**List of features:**

* It uses a new Certainty formula as splitting criteria.
* Creates full trees, showing error rates for train and test data, attribute importance, causes and false positives/negatives.
* If no test data is provided, it can split the train dataset in 80% for training and 20% for testing.
* Creates random forests, showing error rates for train and test data, attribute importance, causes and false positives/negatives. Random forests are created in parallel, so it is very fast.
* Creates 10 Fold Cross-Validation for trees and random forests, showing error rates, mean and Standard Error and false positives/negatives. Cross-Validation folds are created in parallel.
* Saves trees and random forests to disk in a compressed file. (E.g. model.tree, model.forest)
* Query trees and random forest from saved files. Queries can contain missing values, just enter the character: “?”.
* Make predictions and fill out cases files with those predictions, either from single trees or random forests.
* Missing values imputation for train and test data is implemented. Continuous attributes are imputed as the mean value. Discrete attributes are imputed as MODE, which selects the value that is most frequent.
* Ignoring attributes is implemented. In the .names file just set the attribute type as: ignore.
* Three different splitting criteria can be used: Certainty, Entropy and Gini. If no criteria is invoked then Certainty will be used.

CID3 requires JDK 15 or higher.

The data format is similar to that of C4.5 and C5.0. The data file format is CSV, and it could be split in two separated files, like: titanic.data and titanic.test.   The class attribute column must be the last column of the file. The other necessary file is the "names" file, which should be named like: titanic.names, and it contains the names and types of the attributes. The first line is the class attribute possible values. This line could be left empty with just a dot(.) Below is an example of the titanic.names file:

```
0,1.  
PassengerId: ignore.  
Pclass: 1,2,3.  
Sex : male,female.  
Age: continuous.  
SibSp: discrete.  
Parch: discrete.  
Ticket: ignore.  
Fare: continuous.  
Cabin: ignore.  
Embarked: discrete.  
```
