## 部署流程

1. git clone项目

2. 在idea中打包maven clean+package（跳过测试——因为没有部署中间件测试会过不去），打包完target目录下有对应jar包。

   ——也可以不打包直接在docker中一键部署，但是下载依赖有点慢会出错。

3. 在docker中进入项目根目录，利用compose build命令构建。具体是什么记不清了，问问AI就行，一条命令就够了，因为compose.yaml已经写好了

4. 部署成功

## 使用

* 可以看看主要API的参数，文档在localhost:8080/doc.html
  * 其中注册后返回的token没有存入redis中，可能是个bug/设计错误，注册完需要登陆
* 文档中每个API接口下有测试窗口，除了user的两个接口不要头其余都要登陆返回的token作头
* 并发测试需要另外下软件