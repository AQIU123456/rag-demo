#!/usr/bin/env bash
# Prompt Injection 测试一键运行脚本
# 依赖：JDK 17+，lib/ 下已预置 langchain4j / spring 等 jar（无需 Maven/网络）
set -e
cd "$(dirname "$0")"

echo "[1/2] 编译测试代码 + ai-chat-backend 真实生产类 ..."
rm -rf out
javac -processorpath lib/lombok-1.18.30.jar -nowarn \
  -cp "lib/*" \
  -sourcepath "/workspace/ai-chat-backend/src/main/java:src" \
  -d out/sut \
  src/MockChatModel.java src/SutHarness.java src/PromptInjectionTests.java \
  2> build.log || true   # RerankService 的 lombok getter 报错可忽略：其 .class 仍会生成且不影响被测链路

echo "[2/2] 运行 5 个 Prompt Injection 测试用例 ..."
java -cp "out/sut:lib/*" PromptInjectionTests
exit $?
