package com.tabula.tabula_backend.flow.service;

import java.util.*;
import com.tabula.tabula_backend.flow.dto.FlowDtos.FlowType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/** First authoring contract: classic standard text nodes, explicit parent/order/jump relations. */
@Component
public class ClassicDialogTranslator {
    private final ObjectMapper json;
    private final FlowJsonCodec codec;
    private final FlowDiagramProjector projector;
    public ClassicDialogTranslator(ObjectMapper json,FlowJsonCodec codec,FlowDiagramProjector projector) {
        this.json=json; this.codec=codec; this.projector=projector;
    }

    public String importSkill(Map<String,Object> skill) {
        return importSkill(skill,null);
    }
    public String importSkill(Map<String,Object> skill,com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.View layout) {
        String raw=codec.encode(skill); codec.validate(raw,FlowType.DIALOG);
        var projection=projector.project("import","VERSION",null,null,raw,layout);
        // A partial projection must never silently replace the full skill at publication time.
        if(projection.diagnostics().stream().anyMatch(d->"ERROR".equals(d.severity()))) throw unsupported();
        var nodes=new ArrayList<Map<String,Object>>();
        for(var node:projection.nodes()) {
            var data=new LinkedHashMap<String,Object>();
            data.put("label",node.data().label()); data.put("title",node.data().original().getOrDefault("title",node.data().label()));
            data.put("sourceId",node.data().sourceId()); data.put("conditions",node.data().condition());
            data.put("original",node.data().original());
            data.put("collapsed",node.data().collapsed());
            var converted=new LinkedHashMap<String,Object>();
            converted.put("id",node.id()); converted.put("type",node.data().layoutOnly()?
                    ("group".equals(node.type())?"group":Objects.toString(node.data().kind(),"visual")):"ibmDialog");
            converted.put("position",node.position()); converted.put("data",data);
            if(node.parentId()!=null) converted.put("parentId",node.parentId());
            if(node.style()!=null) converted.put("style",node.style());
            nodes.add(converted);
        }
        var canvas=new LinkedHashMap<String,Object>();
        canvas.put("schemaVersion",1); canvas.put("nodes",nodes); canvas.put("edges",projection.edges());
        canvas.put("viewport",projection.viewport()!=null?projection.viewport():Map.of("x",0,"y",0,"zoom",1)); canvas.put("variables",Map.of());
        canvas.put("ibm",skill);
        String result=codec.encode(canvas); codec.validate(result,FlowType.LOCAL); return result;
    }

    @SuppressWarnings("unchecked")
    public String exportSkill(String raw) {
        codec.validate(raw,FlowType.LOCAL);
        var root=(Map<String,Object>)json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(raw);
        var skill=root.get("ibm") instanceof Map<?,?> original ? new LinkedHashMap<>((Map<String,Object>)original):new LinkedHashMap<String,Object>();
        var workspace=skill.get("workspace") instanceof Map<?,?> original ? new LinkedHashMap<>((Map<String,Object>)original):new LinkedHashMap<String,Object>();
        var nodes=new LinkedHashMap<String,Map<String,Object>>();
        var sourceIds=new HashSet<String>();
        for(var item:(List<Map<String,Object>>)root.get("nodes")) {
            if(!(item.get("type") instanceof String type)||!Set.of("text","ibmDialog").contains(type)) throw unsupported();
            var data=(Map<String,Object>)item.get("data");
            var node=data.get("original") instanceof Map<?,?> original ? new LinkedHashMap<>((Map<String,Object>)original):new LinkedHashMap<String,Object>();
            if(!"standard".equals(node.getOrDefault("type","standard"))) throw unsupported();
            String id=data.get("sourceId") instanceof String s?s:"node_"+codec.requestHash(item.get("id"));
            if(!id.matches("[A-Za-z0-9_.-]{1,128}")||!sourceIds.add(id)) throw unsupported();
            node.put("dialog_node",id); node.put("type","standard");
            if(data.get("title")!=null) node.put("title",text(data.get("title"),512));
            if(data.get("conditions")!=null) node.put("conditions",text(data.get("conditions"),2048));
            else if(!node.containsKey("conditions")) node.put("conditions","true");
            if(data.containsKey("text")) {
                replaceText(node,text(data.get("text"),10000));
            }
            if(data.containsKey("variables")) {
                if(!(data.get("variables") instanceof Map<?,?>)) throw unsupported();
                node.put("context",data.get("variables"));
            }
            if(node.get("next_step") instanceof Map<?,?> next && !"jump_to".equals(next.get("behavior"))) throw unsupported();
            node.remove("parent"); node.remove("previous_sibling"); node.remove("next_step");
            nodes.put((String)item.get("id"),node);
        }
        var previous=new HashMap<String,String>(); var parents=new HashMap<String,String>(); var jumps=new HashSet<String>();
        for(var edge:(List<Map<String,Object>>)root.get("edges")) {
            var from=nodes.get(edge.get("source")); var to=nodes.get(edge.get("target"));
            String fromId=(String)from.get("dialog_node"),toId=(String)to.get("dialog_node");
            var data=edge.get("data") instanceof Map<?,?> m?m:Map.of();
            String relation=data.get("relation") instanceof String s?s:"JUMP";
            switch(relation) {
                case "PARENT" -> { if(parents.put(toId,fromId)!=null) throw unsupported(); to.put("parent",fromId); }
                case "EVALUATION_ORDER" -> { if(previous.put(toId,fromId)!=null) throw unsupported(); to.put("previous_sibling",fromId); }
                case "JUMP" -> {
                    if(!jumps.add(fromId)) throw unsupported();
                    String selector=data.get("selector") instanceof String s?s:"body";
                    if(!Set.of("body","condition").contains(selector)) throw unsupported();
                    from.put("next_step",Map.of("behavior","jump_to","dialog_node",toId,"selector",selector));
                }
                default -> throw unsupported();
            }
        }
        var byId=new HashMap<String,Map<String,Object>>(); nodes.values().forEach(n -> byId.put((String)n.get("dialog_node"),n));
        acyclic(parents); acyclic(previous);
        if(previous.isEmpty()) {
            var last=new HashMap<String,String>();
            for(var n:nodes.values()) {
                String group=Objects.toString(n.get("parent"),"");
                String prior=last.put(group,(String)n.get("dialog_node"));
                if(prior!=null) n.put("previous_sibling",prior);
            }
        }
        var heads=new HashMap<String,Integer>(); var successors=new HashSet<String>();
        for(var n:nodes.values()) {
            String group=Objects.toString(n.get("parent"),"");
            if(n.get("previous_sibling") instanceof String prior) {
                if(!Objects.equals(n.get("parent"),byId.get(prior).get("parent"))||!successors.add(prior)) throw unsupported();
            } else heads.merge(group,1,Integer::sum);
        }
        if(heads.values().stream().anyMatch(count->count!=1)) throw unsupported();
        if(root.get("variables") instanceof Map<?,?> variables && !variables.isEmpty()) {
            if(nodes.isEmpty()) throw unsupported();
            var first=nodes.values().iterator().next();
            var context=new LinkedHashMap<String,Object>(); context.putAll((Map<String,Object>)variables);
            if(first.get("context") instanceof Map<?,?> c) context.putAll((Map<String,Object>)c);
            first.put("context",context);
        }
        workspace.put("dialog_nodes",new ArrayList<>(nodes.values()));
        workspace.putIfAbsent("intents",List.of()); workspace.putIfAbsent("entities",List.of());
        skill.put("type","dialog"); skill.put("workspace",workspace);
        String result=codec.encode(skill); codec.validate(result,FlowType.DIALOG); return result;
    }
    private static void acyclic(Map<String,String> links) {
        var done=new HashSet<String>();
        for(String start:links.keySet()) {
            var seen=new HashSet<String>(); String cursor=start;
            while(cursor!=null&&!done.contains(cursor)) { if(!seen.add(cursor)) throw unsupported(); cursor=links.get(cursor); }
            done.addAll(seen);
        }
    }
    private static String text(Object value,int max) {
        if(!(value instanceof String s)||s.length()>max) throw unsupported(); return s;
    }
    @SuppressWarnings("unchecked")
    private static void replaceText(Map<String,Object> node,String text) {
        var output=node.get("output") instanceof Map<?,?> m?new LinkedHashMap<>((Map<String,Object>)m):new LinkedHashMap<String,Object>();
        if(output.containsKey("text")) throw unsupported(); // Legacy expression/output form needs a separate mapping.
        if(output.containsKey("generic") && !(output.get("generic") instanceof List<?>)) throw unsupported();
        var generic=output.get("generic") instanceof List<?> list?new ArrayList<>((List<Object>)list):new ArrayList<Object>();
        if(generic.isEmpty()) generic.add(Map.of("response_type","text","values",List.of(Map.of("text",text))));
        else {
            if(!(generic.getFirst() instanceof Map<?,?> first)||!"text".equals(first.get("response_type"))) throw unsupported();
            var response=new LinkedHashMap<>((Map<String,Object>)first);
            if(!(response.get("values") instanceof List<?> list)||list.isEmpty()||!(list.getFirst() instanceof Map<?,?> value)||value.containsKey("text_expression")) throw unsupported();
            var values=new ArrayList<>((List<Object>)list); var replacement=new LinkedHashMap<>((Map<String,Object>)value);
            replacement.put("text",text); values.set(0,replacement); response.put("values",values); generic.set(0,response);
        }
        output.put("generic",generic); node.put("output",output);
    }
    private static FlowException unsupported() {
        return new FlowException(HttpStatus.UNPROCESSABLE_ENTITY,"DIALOG_TRANSLATION_UNSUPPORTED","O canvas contém elementos incompatíveis com o contrato inicial de Dialog clássico.");
    }
}
