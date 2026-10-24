package com.gsic.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 思考者：接收用户指令，拆解为详细的执行计划，再指挥 agent 执行。
 * 与 agent 融为一体，合称"思考执行员"。
 *
 * 例如用户说"自拍"，思考者会规划：
 * 打开相机（无障碍/root/termux 任选）→ 确认前置摄像头 →
 * 找到快门按钮 → 执行拍摄。
 */
public class Thinker {

    public static class Plan {
        /** 整体思路 */
        public String approach = "";
        /** 拆解步骤 */
        public List<String> steps = new ArrayList<>();
        /** 注意事项 */
        public String notes = "";
        public boolean ok = false;

        public String toText() {
            StringBuilder sb = new StringBuilder();
            if (!approach.isEmpty()) sb.append("思路：").append(approach).append("\n");
            for (int i = 0; i < steps.size(); i++) {
                sb.append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
            if (!notes.isEmpty()) sb.append("注意：").append(notes);
            return sb.toString().trim();
        }
    }

    /**
     * @param goal   用户原始指令
     * @param screen 当前界面状态（紧凑文本）
     */
    public static Plan plan(LlmClient client, String goal, String screen) {
        Plan p = new Plan();
        try {
            String prompt = "你是手机任务的思考者，负责把用户的指令拆解为详细的执行计划。\n\n"
                    + "用户指令：" + goal + "\n"
                    + "当前手机界面：\n" + screen + "\n\n"
                    + "请思考这个任务应该怎么完成，并拆解为具体步骤。\n"
                    + "可用的执行方式：\n"
                    + "- termux 命令行（⭐首选）：内置 Termux 环境，"
                    + "能用命令搞定的（查资料、计算、文件、联网取数、跑脚本）优先用它，别点 UI\n"
                    + "- 联网搜索 search：需要外部信息时先搜索，再把结果用到后续步骤\n"
                    + "- 视觉 vision：文本抓不到按钮时（如相机快门是图标、"
                    + "判断前后摄像头），用 vision 看屏幕问位置\n"
                    + "- 无障碍操作：点击/输入/滑动（termux 搞不定才用，如普通 App 界面）\n"
                    + "- root 命令：shell（适合系统级操作，如 screencap、am start）\n"
                    + "- 技能卡：如有现成的直接调用\n\n"
                    + "要求：\n"
                    + "1. 步骤要具体、可执行，像给执行者下达指令一样详细\n"
                    + "2. 每个步骤注明建议的执行方式\n"
                    + "3. 考虑边界情况（如：确认前置摄像头、找不到按钮时的备选）\n\n"
                    + "只输出纯 JSON：\n"
                    + "{\"approach\":\"整体思路一句话\","
                    + "\"steps\":[\"步骤1（方式）\",\"步骤2（方式）\"],"
                    + "\"notes\":\"注意事项\"}";
            LlmClient.Result r = client.chat(prompt);
            if (!r.ok) return p;
            JSONObject o = AgentLoop.extractJson(r.text);
            if (o == null) return p;
            p.approach = o.optString("approach", "");
            JSONArray arr = o.optJSONArray("steps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    p.steps.add(arr.optString(i, ""));
                }
            }
            p.notes = o.optString("notes", "");
            p.ok = !p.steps.isEmpty();
        } catch (Exception ignored) {
        }
        return p;
    }
}
