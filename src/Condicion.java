/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */



import java.io.Serializable;

public class Condicion implements Serializable {

    private String atributo;
    private String valor;

    public Condicion(String atributo, String valor) {
        this.atributo = atributo;
        this.valor = valor;
    }

    public String getAtributo() {
        return atributo;
    }

    public void setAtributo(String atributo) {
        this.atributo = atributo;
    }

    public String getValor() {
        return valor;
    }

    public void setValor(String valor) {
        this.valor = valor;
    }

    @Override
    public boolean equals(Object other) {

        if ( ! ( other instanceof Condicion ) ) return false;

        Condicion cond = (Condicion) other;

        return this.atributo.equals( cond.atributo ) &&
               this.valor.equals( cond.valor );

    }



}
