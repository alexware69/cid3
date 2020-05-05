/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

import java.io.*;
import java.util.*;
import org.jdom2.*;
import org.jdom2.output.*;


public class GuardadorDeReglas 
        implements Runnable,Serializable {

    private String nombres[];
    private ArrayList valores[];
    private Element root;
    private int clase;

    public GuardadorDeReglas() {
    }

    void guardarAtributos(String[] nombres, ArrayList[] valores, Element arbol, int atributoClase) {
        this.nombres = nombres;
        this.valores = valores;
        this.root = arbol;
        this.clase = atributoClase;

        new Thread(this).start();
    }

    public void run() {

        Element listAtrib = new Element("lista-de-atributos");

        for (int i = 0; i < nombres.length; i++) {

            if ( clase == i ) continue;

            Element nombre = new Element("nombre");
            nombre.addContent( nombres[i] );

            Element listV = new Element("lista-de-valores");

            int cant = valores[i].size();
            for (int j = 0; j < cant; j++) {
                Element valor = new Element("valor");
                valor.addContent( valores[i].get(j).toString() );
                listV.addContent(valor);
            }

            Element atrib = new Element("atributo");
            atrib.addContent(nombre);
            atrib.addContent(listV);

            listAtrib.addContent(atrib);
        }

        try {

            File file = new File("misreglas.xml");
            if ( file.exists() ) file.delete();

            FileOutputStream fos = new FileOutputStream( file );

            Element clase = new Element("clase");
            clase.addContent( nombres[this.clase] );

            Element raiz = new Element("cid3");
            
            raiz.addContent(clase);
            raiz.addContent(listAtrib);
            raiz.addContent(root);

            Document doc = new Document(raiz);

            Format f = Format.getPrettyFormat();
            XMLOutputter out = new XMLOutputter(f);
            out.output(doc, fos);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
