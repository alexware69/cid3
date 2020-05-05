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
public class SumUnderAndOver implements Serializable {
    int under;
    int over;

    public SumUnderAndOver(int u, int o){
        under = u;
        over = o;
    }
}
