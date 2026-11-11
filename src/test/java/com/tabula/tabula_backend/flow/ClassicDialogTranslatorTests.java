package com.tabula.tabula_backend.flow;

import com.tabula.tabula_backend.flow.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ClassicDialogTranslatorTests {
    private final JsonMapper json=JsonMapper.builder().build();
    private final FlowJsonCodec codec=new FlowJsonCodec(json);
    private final ClassicDialogTranslator translator=new ClassicDialogTranslator(json,codec,new FlowDiagramProjector(json,codec));
    private static final String CANVAS="""
        {"schemaVersion":1,"nodes":[
          {"id":"a","type":"text","position":{"x":0,"y":0},"data":{"title":"Entrada","conditions":"welcome","text":"Olá"}},
          {"id":"b","type":"text","position":{"x":300,"y":0},"data":{"conditions":"true","text":"Fim"}}
        ],"edges":[{"id":"e","source":"a","target":"b"}],"variables":{"count":1},"viewport":{"x":0,"y":0,"zoom":1}}
        """;
    @Test void generatesDeterministicClassicDialogIdsTextConditionsJumpAndContext() {
        String converted=translator.exportSkill(CANVAS);
        assertThat(converted).isEqualTo(translator.exportSkill(CANVAS));
        var skill=json.readTree(converted); var nodes=skill.get("workspace").get("dialog_nodes");
        assertThat(nodes.size()).isEqualTo(2);
        assertThat(nodes.get(0).get("conditions").asText()).isEqualTo("welcome");
        assertThat(nodes.get(0).get("next_step").get("dialog_node").asText()).isEqualTo(nodes.get(1).get("dialog_node").asText());
        assertThat(nodes.get(0).get("context").get("count").asInt()).isEqualTo(1);
        assertThat(nodes.get(1).get("previous_sibling").asText()).isEqualTo(nodes.get(0).get("dialog_node").asText());
        assertThat(nodes.get(0).get("output").get("generic").get(0).get("values").get(0).get("text").asText()).isEqualTo("Olá");
    }
    @Test void importedWorkspaceKeepsUnknownDataAndAdditionalResponsesWhenFirstTextChanges() {
        var skill=new LinkedHashMap<String,Object>(); skill.put("type","dialog"); skill.put("future",Map.of("kept",true));
        skill.put("workspace",Map.of("entities",List.of(),"intents",List.of(Map.of("intent","hello","examples",List.of())),
                "dialog_nodes",List.of(Map.of("dialog_node","entry","conditions","welcome","type","standard","future",42,
                        "output",Map.of("custom",true,"generic",List.of(
                            Map.of("response_type","text","selection_policy","random","values",List.of(Map.of("text","Old","future",true),Map.of("text","Alternative"))),
                            Map.of("response_type","image","source","https://example.invalid/image")))))));
        String imported=translator.importSkill(skill);
        @SuppressWarnings("unchecked") var root=json.readValue(imported,Map.class);
        @SuppressWarnings("unchecked") var data=(Map<String,Object>)((Map<?,?>)((List<?>)root.get("nodes")).getFirst()).get("data");
        data.put("text","Changed");
        String published=translator.exportSkill(json.writeValueAsString(root));
        assertThat(published).contains("Changed","Alternative","selection_policy","custom","future","image","hello");
        assertThat(published).doesNotContain("\"text\":\"Old\"");
    }
    @Test void rejectsUnsupportedNodesBranchingAndInvalidStructuralRelations() {
        assertThatThrownBy(()->translator.exportSkill(CANVAS.replace("\"type\":\"text\",", ""))).isInstanceOf(FlowException.class);
        assertThatThrownBy(()->translator.exportSkill(CANVAS.replace("\"type\":\"text\"","\"type\":\"generative\""))).isInstanceOf(FlowException.class);
        String branch=CANVAS.replace("\"edges\":[{\"id\":\"e\",\"source\":\"a\",\"target\":\"b\"}]",
                "\"edges\":[{\"id\":\"e\",\"source\":\"a\",\"target\":\"b\"},{\"id\":\"e2\",\"source\":\"a\",\"target\":\"a\"}]");
        assertThatThrownBy(()->translator.exportSkill(branch)).isInstanceOf(FlowException.class);
        String parentCycle=CANVAS.replace("\"edges\":[{\"id\":\"e\",\"source\":\"a\",\"target\":\"b\"}]",
                "\"edges\":[{\"id\":\"e\",\"source\":\"a\",\"target\":\"b\",\"data\":{\"relation\":\"PARENT\"}},{\"id\":\"e2\",\"source\":\"b\",\"target\":\"a\",\"data\":{\"relation\":\"PARENT\"}}]");
        assertThatThrownBy(()->translator.exportSkill(parentCycle)).isInstanceOf(FlowException.class);
    }
}
