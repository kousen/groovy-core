/*
 * Copyright 2003-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.classgen.asm.indy;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.classgen.AsmClassGenerator;
import org.codehaus.groovy.classgen.asm.CompileStack;
import org.codehaus.groovy.classgen.asm.InvocationWriter;
import org.codehaus.groovy.classgen.asm.MethodCallerMultiAdapter;
import org.codehaus.groovy.classgen.asm.OperandStack;
import org.codehaus.groovy.classgen.asm.WriterController;
import org.codehaus.groovy.vmplugin.v7.IndyInterface;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;
import static org.codehaus.groovy.classgen.asm.BytecodeHelper.*;

/**
 * This Writer is used to generate the call invocation byte codes
 * for usage by invokedynamic.
 * 
 * @author <a href="mailto:blackdrag@gmx.org">Jochen "blackdrag" Theodorou</a>
 */
public class InvokeDynamicWriter extends InvocationWriter {
    
    private static final Handle BSM = 
        new Handle(
                H_INVOKESTATIC,
                IndyInterface.class.getName().replace('.', '/'),
                "bootstrap",
                MethodType.methodType(
                        CallSite.class, Lookup.class, String.class, MethodType.class
                ).toMethodDescriptorString());

    
    
    private WriterController controller;

    public InvokeDynamicWriter(WriterController wc) {
        super(wc);
        this.controller = wc;
    }

    @Override
    public void makeCall(
            Expression origin,  Expression receiver,
            Expression message, Expression arguments,
            MethodCallerMultiAdapter adapter, 
            boolean safe, boolean spreadSafe, boolean implicitThis 
    ) {
        // direct method call
        boolean containsSpreadExpression = AsmClassGenerator.containsSpreadExpression(arguments);
        if (!containsSpreadExpression && origin instanceof MethodCallExpression) {
            MethodCallExpression mce = (MethodCallExpression) origin;
            MethodNode target = mce.getMethodTarget();
            if (writeDirectMethodCall(target, implicitThis, receiver, makeArgumentList(arguments))) return;
        }

        // no direct method call, dynamic call site        
        ClassNode cn = controller.getClassNode();
        if (controller.isInClosure() && !implicitThis && AsmClassGenerator.isThisExpression(receiver)) cn=cn.getOuterClass();
        ClassExpression sender = new ClassExpression(cn);

        OperandStack operandStack = controller.getOperandStack();
        CompileStack compileStack = controller.getCompileStack();
        AsmClassGenerator acg = controller.getAcg();
        MethodVisitor mv = controller.getMethodVisitor();
        
        // fixed number of arguments && name is a real String and no GString
        if ((adapter == invokeMethod || adapter == invokeMethodOnCurrent || adapter == invokeStaticMethod) && !spreadSafe) {
            String methodName = getMethodName(message);
            
            if (methodName != null) {
                compileStack.pushLHS(false);

                String sig = "(";
                receiver.visit(controller.getAcg());
                sig += getTypeDescription(operandStack.getTopOperand());
                int numberOfArguments = 1;
                
                ArgumentListExpression ae = makeArgumentList(arguments);
                for (Expression arg : ae.getExpressions()) {
                    arg.visit(controller.getAcg());
                    sig += getTypeDescription(operandStack.getTopOperand());
                    numberOfArguments++;
                }
                sig += ")Ljava/lang/Object;";
                
                mv.visitInvokeDynamicInsn(methodName, sig, BSM);
//                controller.getCallSiteWriter().makeCallSite(
//                        receiver, methodName, arguments, safe, implicitThis, 
//                        adapter == invokeMethodOnCurrent, 
//                        adapter == invokeStaticMethod);
                
                operandStack.replace(ClassHelper.OBJECT_TYPE, numberOfArguments);
                compileStack.popLHS();
                return;
            }
        }

        // variable number of arguments wrapped in a Object[]
        
        
        // ensure VariableArguments are read, not stored
        compileStack.pushLHS(false);

        // sender only for call sites
        if (adapter == AsmClassGenerator.setProperty) {
            ConstantExpression.NULL.visit(acg);
        } else {
            sender.visit(acg);
        }
        
        // receiver
        compileStack.pushImplicitThis(implicitThis);
        receiver.visit(acg);
        operandStack.box();
        compileStack.popImplicitThis();
        
        
        int operandsToRemove = 2;
        // message
        if (message != null) {
            message.visit(acg);
            operandStack.box();
            operandsToRemove++;
        }

        // arguments
        int numberOfArguments = containsSpreadExpression ? -1 : AsmClassGenerator.argumentSize(arguments);
        if (numberOfArguments > MethodCallerMultiAdapter.MAX_ARGS || containsSpreadExpression) {
            ArgumentListExpression ae = makeArgumentList(arguments);
            if (containsSpreadExpression) {
                acg.despreadList(ae.getExpressions(), true);
            } else {
                ae.visit(acg);
            }
        } else if (numberOfArguments > 0) {
            operandsToRemove += numberOfArguments;
            TupleExpression te = (TupleExpression) arguments;
            for (int i = 0; i < numberOfArguments; i++) {
                Expression argument = te.getExpression(i);
                argument.visit(acg);
                operandStack.box();
                if (argument instanceof CastExpression) acg.loadWrapper(argument);
            }
        }

        adapter.call(controller.getMethodVisitor(), numberOfArguments, safe, spreadSafe);

        compileStack.popLHS();
        operandStack.replace(ClassHelper.OBJECT_TYPE,operandsToRemove);
    }
}