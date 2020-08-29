/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */


import java.io.Serializable;
import java.util.*;
/**
 *
 * @author alex
 */
public class DataFrequencies implements Serializable {
    public ArrayList<cid3.DataPoint> data;
    public int[] frequencyClasses;  
    
    public DataFrequencies(ArrayList<cid3.DataPoint> d, int[] f){
        data = d;
        frequencyClasses = f;
    }
}
