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
    public SumBelowAndAbove[] sumsClassesAndAttribute;
    double value;

    public Threshold(double t, SumBelowAndAbove[] s){
        value = t;
        sumAUnder = 0;
        sumAOver = 0;
        sumsClassesAndAttribute = s;
    }
}
