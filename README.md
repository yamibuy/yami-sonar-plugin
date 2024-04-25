# 对应的sonar版本
* sonar 版本8.9.0。 
* git记录 https://github.com/SonarSource/sonar-java/tree/4eac8639211aaf3c45d0c2f5f9d651c5025c7d76

# 插件开发文档
* https://github.com/SonarSource/sonar-java/blob/master/docs/CUSTOM_RULES_101.md

# 打包部署
1. 先打包
```shell
mvn clean package
```
2. 将打包后的文件移动到这个目录`移动到 SonarQube 实例的扩展文件夹中，该文件夹位于 $SONAR_HOME/extensions /插件。`
3. 重启SonarQube实例
