package com.example.aichat.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具
 * 提供 Mock 的天气查询功能
 */
@Component
public class WeatherTool {

    /**
     * 查询指定城市的天气
     * @param city 城市名称
     * @return 天气信息
     */
    @Tool("查询指定城市的天气信息，包括温度、天气状况等")
    public String getWeather(String city) {
        // Mock 天气数据
        String[] weatherConditions = {"晴", "多云", "小雨", "大雨", "雷阵雨", "雪", "雾"};
        int randomIndex = (int) (Math.random() * weatherConditions.length);
        String condition = weatherConditions[randomIndex];
        
        // 随机生成温度（-10 到 35 度之间）
        int temperature = (int) (Math.random() * 46) - 10;
        
        // 随机生成湿度（30% 到 90% 之间）
        int humidity = 30 + (int) (Math.random() * 61);
        
        // 随机生成风力等级（1 到 8 级）
        int windLevel = 1 + (int) (Math.random() * 8);
        
        return String.format(
            "【%s天气】\n" +
            "天气状况：%s\n" +
            "温度：%d°C\n" +
            "湿度：%d%%\n" +
            "风力：%d级",
            city, condition, temperature, humidity, windLevel
        );
    }
}
