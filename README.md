# CID3

This is a full-featured Decision Trees and Random Forests generator. It can save trees or forests to disk for later use. It is possible to query trees and Random Forests and to fill out an unlabeled file with the predicted classes. Documentation is not yet available, although the program options can be shown with command: 
java -jar cid3.jar -h.

It requires JDK 15 or higher.

The data format is similar to that of C4.5 and C5.0. The data file format is CSV, and it could be split in two separated files, like: titanic.data and titanic.test.   The class attribute column must be the last column of the file. The other necessary file is the "names" file, which should be named like: titanic.names, and it contains the names and types of the attributes. The first line is the class attribute possible values. This line could be left empty with just a dot(.) Below is an example of the titanic.names file:

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
