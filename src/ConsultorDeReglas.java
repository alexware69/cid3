/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

import java.io.*;
import java.util.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import org.jdom2.*;
import org.jdom2.input.*;


public class ConsultorDeReglas implements Serializable{

    private final static String file = "misreglas.xml";
    private Document archivo;

    private void abrirArchivo() throws JDOMException, IOException {

        SAXBuilder in = new SAXBuilder();
        archivo = in.build( file );
    }

    public List<String> getResultados(List<Condicion> condiciones) throws JDOMException, IOException {

        abrirArchivo();

        Element id3 = archivo.getRootElement();

        Element add = id3.getChild("arbol-de-decision");

        List cond = new LinkedList(condiciones);

        return getResult(add, cond);

    }

    private List<String> getResult( Element root, List<Condicion> condiciones ) {
        
        Element ler = root.getChild("lista-de-resultados");

        if ( ler != null ) {

            List vals = ler.getChildren("resultado");
            List<String> resultados = new LinkedList<String>();
            for (int i = 0; i < vals.size(); i++) {
                Element result = (Element) vals.get(i);
                resultados.add( result.getText() );
            }

            return resultados;
        }

        Iterator it = root.getChildren("condicion").iterator();
        
        while (it.hasNext()) {

            Element cond = (Element) it.next();

            String atrib = cond.getChildText("atributo");
            String valor = cond.getChildText("valor");

            Condicion con = new Condicion(atrib, valor);

            for (Condicion condicion : condiciones) {

                if ( con.equals(condicion) ) {
                    condiciones.remove(condicion);
                    return getResult(cond, condiciones);
                }
            }
        }

        return null;
    }

    public TreeModel getTree() throws JDOMException, IOException {

        DefaultMutableTreeNode node = new DefaultMutableTreeNode("cid3");
        DefaultTreeModel dtm = new DefaultTreeModel( node );

        abrirArchivo();

        Element id3 = archivo.getRootElement();

        Element add = id3.getChild("arbol-de-decision");

        String clase = id3.getChildText("clase");

        llenar( add, node, clase );

        return dtm;
    }

    private void llenar( Element root, DefaultMutableTreeNode node, String clase) {

        Element ler = root.getChild("lista-de-resultados");

        if ( ler != null ) {

            List vals = ler.getChildren("resultado");

            String str = "";
            for (int i = 0; i < vals.size(); i++) {
                Element result = (Element) vals.get(i);
                
                str += " " + result.getText();
            }

            DefaultMutableTreeNode hoja = new DefaultMutableTreeNode( clase +  " =" + str );
            node.add(hoja);

            return;
        }

        Iterator it = root.getChildren("condicion").iterator();

        while (it.hasNext()) {

            Element cond = (Element) it.next();

            String atrib = cond.getChildText("atributo");
            String valor = cond.getChildText("valor");

            DefaultMutableTreeNode dec = new DefaultMutableTreeNode(atrib + ": " + valor);

            llenar(cond, dec, clase);

            node.add(dec);
        }
    }
}
