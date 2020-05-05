/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

import java.io.Serializable;

/**
 *
 * @author alex
 */
public class Threshold implements Serializable {
    public int sumAUnder;
    public int sumAOver;
    public SumUnderAndOver[] sumsClassesAndAttribute;
    double value;

    public Threshold(int uA, int oA, int uCandA, int oCandA, double v){
        sumAUnder = uA;
        sumAOver = oA;
        value = v;
    }
    public Threshold(double t, SumUnderAndOver[] s){
        value = t;
        sumAUnder = 0;
        sumAOver = 0;
        sumsClassesAndAttribute = s;
        //sumCandAUnder = 0;
        //sumCandAOver = 0;
    }
}
