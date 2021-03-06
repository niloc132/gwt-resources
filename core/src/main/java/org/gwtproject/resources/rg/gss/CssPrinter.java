/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.gwtproject.resources.rg.gss;

import com.google.common.css.compiler.ast.*;
import org.gwtproject.resources.rg.Generator;
import org.gwtproject.resources.rg.gss.ast.CssDotPathNode;
import org.gwtproject.resources.rg.gss.ast.CssJavaExpressionNode;
import org.gwtproject.resources.rg.gss.ast.CssRuntimeConditionalRuleNode;

import java.util.Stack;

import static com.google.common.css.compiler.ast.CssAtRuleNode.Type;

/**
 * Visitor that converts the AST to a {@code String} that can be evaluated as a Java expression.
 *
 * <p>For example, the following GSS code
 * <pre>
 *   @if(eval("com.foo.bar()")) {
 *     .foo {
 *       padding: 5px;
 *     }
 *   }
 *   {@literal @}else {
 *     .foo {
 *       padding: 15px;
 *     }
 *   }
 *   .bar {
 *     width:10px;
 *   }
 * }
 * </pre>
 * will be translated to
 * {@code "(com.foo.bar() ? (\".foo{padding:5px}\") : (\".foo{padding:15px}\")) + (\".bar{width:10px}\")"}
 */
public class CssPrinter extends GssCompactPrinter {
    /**
     * This value is used by {@link #concat} to help create a more balanced AST
     * tree by producing parenthetical expressions.
     */
    private static final int CONCAT_EXPRESSION_LIMIT = 20;
    private static final String CONTATENATION = " + ";
    private static final String COLON = " : ";
    private static final String LEFT_PARENTHESIS = "(";
    private static final String RIGHT_PARENTHESIS = ")";
    private static final String DOUBLE_QUOTE = "\"";
    private static final String CONTATENATION_BLOCK = ") + (";
    private static final String CONDITIONAL_OPERATOR = ") ? (";

    private final Stack<Boolean> elseNodeFound = new Stack<>();

    private StringBuilder masterStringBuilder;
    private String css;
    private int concatenationNumber;

    public CssPrinter(CssTree tree) {
        super(tree);
    }

    public CssPrinter(CssNode node) {
        super(node);
    }

    @Override
    public boolean enterTree(CssRootNode root) {
        masterStringBuilder.append(LEFT_PARENTHESIS);
        return super.enterTree(root);
    }

    @Override
    public String getCompactPrintedString() {
        return css;
    }

    @Override
    public void leaveTree(CssRootNode root) {
        masterStringBuilder.append(flushInternalStringBuilder()).append(RIGHT_PARENTHESIS);
        super.leaveTree(root);
    }

    /**
     * Read what the internal StringBuilder used by the CompactPrinter has already built. Escape it.
     * and reset the internal StringBuilder
     *
     * @return
     */
    private String flushInternalStringBuilder() {
        // NOTE(flan): Note that you have to be careful where you do this. Internally,
        // the compact printer sometimes deletes characters from the end of the stringBuilder to save
        // space. I believe that you'll be safe because, if there's nothing in the buffer, there is
        // nothing to delete, but you may have some unnecessary characters in the output. you may
        // want to call that out explicitly in the code.
        String content = DOUBLE_QUOTE + Generator.escape(getOutputBuffer()) + DOUBLE_QUOTE;
        resetBuffer();

        return content;
    }

    @Override
    public void runPass() {
        masterStringBuilder = new StringBuilder();
        concatenationNumber = 0;

        super.runPass();

        css = masterStringBuilder
                .toString()
                // remove empty string concatenation : '+ ("")'
                .replaceAll(" \\+ \\(\"\"\\)", "")
                // remove possible empty string concatenation '("") + ' at the  beginning
                .replaceAll("^\\(\"\"\\) \\+ ", "");
    }

    @Override
    public boolean enterConditionalBlock(CssConditionalBlockNode node) {
        masterStringBuilder.append(flushInternalStringBuilder());

        masterStringBuilder.append(CONTATENATION_BLOCK);

        elseNodeFound.push(false);

        return true;
    }

    @Override
    public void leaveConditionalBlock(CssConditionalBlockNode block) {
        if (!elseNodeFound.pop()) {
            masterStringBuilder.append(DOUBLE_QUOTE).append(DOUBLE_QUOTE);
        }
        masterStringBuilder.append(CONTATENATION_BLOCK);

        // Reset concatenation counter
        concatenationNumber = 0;
    }

    @Override
    public boolean enterConditionalRule(CssConditionalRuleNode node) {
        if (node.getType() == Type.ELSE) {
            elseNodeFound.pop();
            elseNodeFound.push(true);

            masterStringBuilder.append(LEFT_PARENTHESIS);
        } else {
            if(node instanceof CssRuntimeConditionalRuleNode){
                CssRuntimeConditionalRuleNode conditionalRuleNode = (CssRuntimeConditionalRuleNode) node;
                masterStringBuilder.append(LEFT_PARENTHESIS);
                masterStringBuilder.append(conditionalRuleNode.getRuntimeCondition().getValue());
                masterStringBuilder.append(CONDITIONAL_OPERATOR);
                // Reset concatenation counter
                concatenationNumber = 0;
            }
        }

        return true;
    }

    @Override
    public void leaveConditionalRule(CssConditionalRuleNode node) {
        masterStringBuilder.append(flushInternalStringBuilder()).append(RIGHT_PARENTHESIS);

        if (node.getType() != Type.ELSE) {
            masterStringBuilder.append(COLON);
        }
    }

    @Override
    protected void appendValueNode(CssValueNode node) {
        if (node instanceof CssJavaExpressionNode || node instanceof CssDotPathNode) {
            concat(LEFT_PARENTHESIS + node.getValue() + RIGHT_PARENTHESIS);
        } else {
            super.appendValueNode(node);
        }
    }

    private void concat(String stringToAppend) {
        masterStringBuilder.append(flushInternalStringBuilder());

        appendConcatOperation();

        masterStringBuilder.append(stringToAppend);

        appendConcatOperation();
    }

    private void appendConcatOperation() {
        // Avoid long string concatenation chain
        if (concatenationNumber >= CONCAT_EXPRESSION_LIMIT) {
            masterStringBuilder.append(CONTATENATION_BLOCK);
            concatenationNumber = 0;
        } else {
            masterStringBuilder.append(CONTATENATION);
            concatenationNumber++;
        }
    }
}
