package com.didazz.descargasocial;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
/** Reads real Android resource XML for host assertions; does not emulate Android rendering/layout. */
final class TestResources {
    static Map<String,String> load(String language) throws Exception {
        String folder=language.equals("es")?"values-es":"values";
        Document d=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Paths.get("app/src/main/res",folder,"strings.xml").toFile());
        Map<String,String> result=new LinkedHashMap<>();NodeList nodes=d.getElementsByTagName("string");
        for(int i=0;i<nodes.getLength();i++){
            Element e=(Element)nodes.item(i);String v=e.getTextContent();
            if(v.startsWith("\"")&&v.endsWith("\""))v=v.substring(1,v.length()-1);
            v=v.replace("\\n","\n").replace("\\'","'").replace("\\\"","\"").replace("\\\\","\\");
            result.put(e.getAttribute("name"),v);
        }
        return result;
    }
    static String render(String language,String value) {
        try {
            Map<String,String> strings=load(language);
            return Messages.render(value,(key,args)->{
                if(!strings.containsKey(key))throw new AssertionError("Missing resource "+key);
                return String.format(Locale.forLanguageTag(language),strings.get(key),args);
            });
        } catch(Exception failure){throw new AssertionError(failure);}
    }
}
