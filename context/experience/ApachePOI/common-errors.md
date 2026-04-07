## 2026-04-07 Apache POI 异常不全是 IOException

- **现象**：测试中传入非法 docx 文件，抛出 `NotOfficeXmlFileException`，但 `catch (IOException)` 未能捕获
- **原因**：`NotOfficeXmlFileException` 继承自 `InvalidFormatException → OpenXML4JException`，不是 IOException 的子类
- **正确做法**：解析 Word 文档时用 `catch (Exception)` 或明确 catch `OpenXML4JException`，不要只 catch IOException
- **适用场景**：任何使用 Apache POI 解析用户上传文件的场景（用户输入不可信）
