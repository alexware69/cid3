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
public class SumBelowAndAbove implements Serializable {
    int below;
    int above;

    public SumBelowAndAbove(int b, int a){
        below = b;
        above = a;
    }
}
