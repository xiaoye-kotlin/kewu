package com.kewu.app.ui

/**
 * 全国高等学校数据（本科 + 高职大专），用于注册页「学校」选择器的模糊搜索。
 *
 * 字段：
 * - [name] 学校全称（展示项）
 * - [py] 全拼小写无空格（"qinghuadaxue"），支持拼音模糊搜
 * - [abbr] 拼音首字母简写（"qhdx"），支持首字母模糊搜
 * - [province] 所在省份
 *
 * ★ 收录口径：教育部《全国高等学校名单》的主力本科 + 高职大专，按省分组。覆盖 985/211/双一流、
 *   省属重点本科与各地主要高职。全量约 2900 所，本文件先收录主力院校（常用、可离线、零依赖）；
 *   搜索未命中时用户仍可「手动输入学校名」兜底（选择器顶部有自由输入项）。
 */
data class University(val name: String, val py: String, val abbr: String, val province: String)

private fun u(name: String, py: String, abbr: String, province: String) = University(name, py, abbr, province)

/**
 * 模糊匹配：命中 名称 / 全拼 / 简写（子串），大小写不敏感。
 */
fun University.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return name.lowercase().contains(q) || py.contains(q) || abbr.contains(q)
}

/** 全国高校列表（省拼音序无关，按展示分组）。 */
val ALL_UNIVERSITIES: List<University> = listOf(
    // ── 北京 ──
    u("清华大学", "qinghuadaxue", "qhdx", "北京"),
    u("北京大学", "beijingdaxue", "bjdx", "北京"),
    u("中国人民大学", "zhongguorenmindaxue", "zgrmdx", "北京"),
    u("北京航空航天大学", "beijinghangkonghangtiandaxue", "bjhkhtdx", "北京"),
    u("北京理工大学", "beijingligongdaxue", "bjlgdx", "北京"),
    u("北京师范大学", "beijingshifandaxue", "bjsfdx", "北京"),
    u("中国农业大学", "zhongguonongyedaxue", "zgnydx", "北京"),
    u("北京邮电大学", "beijingyoudiandaxue", "bjyddx", "北京"),
    u("北京交通大学", "beijingjiaotongdaxue", "bjjtdx", "北京"),
    u("北京科技大学", "beijingkejidaxue", "bjkjdx", "北京"),
    u("北京化工大学", "beijinghuagongdaxue", "bjhgdx", "北京"),
    u("北京工业大学", "beijinggongyedaxue", "bjgydx", "北京"),
    u("对外经济贸易大学", "duiwaijingjimaoyidaxue", "dwjjmydx", "北京"),
    u("中央财经大学", "zhongyangcaijingdaxue", "zycjdx", "北京"),
    u("北京外国语大学", "beijingwaiguoyudaxue", "bjwgydx", "北京"),
    u("中国政法大学", "zhongguozhengfadaxue", "zgzfdx", "北京"),
    u("华北电力大学", "huabeidianlidaxue", "hbdldx", "北京"),
    u("中国传媒大学", "zhongguochuanmeidaxue", "zgcmdx", "北京"),
    u("首都师范大学", "shoudushifandaxue", "sdsfdx", "北京"),
    u("北京林业大学", "beijinglinyedaxue", "bjlydx", "北京"),
    u("中国石油大学（北京）", "zhongguoshiyoudaxuebeijing", "zgsydxbj", "北京"),
    u("中国地质大学（北京）", "zhongguodizhidaxuebeijing", "zgdzdxbj", "北京"),
    u("中国矿业大学（北京）", "zhongguokuangyedaxuebeijing", "zgkydxbj", "北京"),
    u("北京中医药大学", "beijingzhongyiyaodaxue", "bjzyydx", "北京"),
    u("中央民族大学", "zhongyangminzudaxue", "zymzdx", "北京"),
    u("北京电子科技职业学院", "beijingdianzikejizhiyexueyuan", "bjdzkjzyxy", "北京"),
    u("北京工业职业技术学院", "beijinggongyezhiyezhishuxueyuan", "bjgyzyjsxy", "北京"),

    // ── 天津 ──
    u("南开大学", "nankaidaxue", "nkdx", "天津"),
    u("天津大学", "tianjindaxue", "tjdx", "天津"),
    u("天津工业大学", "tianjingongyedaxue", "tjgydx", "天津"),
    u("天津师范大学", "tianjinshifandaxue", "tjsfdx", "天津"),
    u("天津医科大学", "tianjinyikedaxue", "tjykdx", "天津"),
    u("天津理工大学", "tianjinligongdaxue", "tjlgdx", "天津"),
    u("天津市职业大学", "tianjinshizhiyedaxue", "tjszydx", "天津"),

    // ── 河北 ──
    u("河北大学", "hebeidaxue", "hbdx", "河北"),
    u("河北工业大学", "hebeigongyedaxue", "hbgydx", "河北"),
    u("燕山大学", "yanshandaxue", "ysdx", "河北"),
    u("河北师范大学", "hebeishifandaxue", "hbsfdx", "河北"),
    u("石家庄职业技术学院", "shijiazhuangzhiyezhishuxueyuan", "sjzzyjsxy", "河北"),

    // ── 山西 ──
    u("太原理工大学", "taiyuanligongdaxue", "tylgdx", "山西"),
    u("山西大学", "shanxidaxue", "sxdx", "山西"),
    u("中北大学", "zhongbeidaxue", "zbdx", "山西"),
    u("山西省财政税务专科学校", "shanxishengcaizhengshuiwuzhuankexuexiao", "sxsczswzkxx", "山西"),

    // ── 内蒙古 ──
    u("内蒙古大学", "neimenggudaxue", "nmgdx", "内蒙古"),
    u("内蒙古工业大学", "neimenggugongyedaxue", "nmggydx", "内蒙古"),

    // ── 辽宁 ──
    u("大连理工大学", "dalianligongdaxue", "dllgdx", "辽宁"),
    u("东北大学", "dongbeidaxue", "dbdx", "辽宁"),
    u("辽宁大学", "liaoningdaxue", "lndx", "辽宁"),
    u("大连海事大学", "dalianhaishidaxue", "dlhsdx", "辽宁"),
    u("沈阳工业大学", "shengyanggongyedaxue", "sygydx", "辽宁"),
    u("辽宁省交通高等专科学校", "liaoningshengjiaotonggaodengzhuankexuexiao", "lnsjtgdzkxx", "辽宁"),

    // ── 吉林 ──
    u("吉林大学", "jilindaxue", "jldx", "吉林"),
    u("东北师范大学", "dongbeishifandaxue", "dbsfdx", "吉林"),
    u("长春理工大学", "changchunligongdaxue", "cclgdx", "吉林"),
    u("长春汽车工业高等专科学校", "changchunqichegongyegaodengzhuankexuexiao", "ccqcgygdzkxx", "吉林"),

    // ── 黑龙江 ──
    u("哈尔滨工业大学", "haerbingongyedaxue", "hebgydx", "黑龙江"),
    u("哈尔滨工程大学", "haerbingongchengdaxue", "hebgcdx", "黑龙江"),
    u("东北林业大学", "dongbeilinyedaxue", "dblydx", "黑龙江"),
    u("东北农业大学", "dongbeinongyedaxue", "dbnydx", "黑龙江"),
    u("黑龙江职业学院", "heilongjiangzhiyexueyuan", "hljzyxy", "黑龙江"),

    // ── 上海 ──
    u("复旦大学", "fudandaxue", "fddx", "上海"),
    u("上海交通大学", "shanghaijiaotongdaxue", "shjtdx", "上海"),
    u("同济大学", "tongjidaxue", "tjdx", "上海"),
    u("华东师范大学", "huadongshifandaxue", "hdsfdx", "上海"),
    u("华东理工大学", "huadongligongdaxue", "hdlgdx", "上海"),
    u("东华大学", "donghuadaxue", "dhdx", "上海"),
    u("上海大学", "shanghaidaxue", "shdx", "上海"),
    u("上海财经大学", "shanghaicaijingdaxue", "shcjdx", "上海"),
    u("上海外国语大学", "shanghaiwaiguoyudaxue", "shwgydx", "上海"),
    u("上海理工大学", "shanghailigongdaxue", "shlgdx", "上海"),
    u("上海工艺美术职业学院", "shanghaigongyimeishuzhiyexueyuan", "shgymszyxy", "上海"),

    // ── 江苏 ──
    u("南京大学", "nanjingdaxue", "njdx", "江苏"),
    u("东南大学", "dongnandaxue", "dndx", "江苏"),
    u("苏州大学", "suzhoudaxue", "szdx", "江苏"),
    u("南京航空航天大学", "nanjinghangkonghangtiandaxue", "njhkhtdx", "江苏"),
    u("南京理工大学", "nanjingligongdaxue", "njlgdx", "江苏"),
    u("河海大学", "hehaidaxue", "hhdx", "江苏"),
    u("江南大学", "jiangnandaxue", "jndx", "江苏"),
    u("南京师范大学", "nanjingshifandaxue", "njsfdx", "江苏"),
    u("中国矿业大学", "zhongguokuangyedaxue", "zgkydx", "江苏"),
    u("南京农业大学", "nanjingnongyedaxue", "njnydx", "江苏"),
    u("南京信息职业技术学院", "nanjingxinxizhiyezhishuxueyuan", "njxxzyjsxy", "江苏"),
    u("无锡职业技术学院", "wuxizhiyezhishuxueyuan", "wxzyjsxy", "江苏"),

    // ── 浙江 ──
    u("浙江大学", "zhejiangdaxue", "zjdx", "浙江"),
    u("宁波大学", "ningbodaxue", "nbdx", "浙江"),
    u("浙江工业大学", "zhejianggongyedaxue", "zjgydx", "浙江"),
    u("杭州电子科技大学", "hangzhoudianzikejidaxue", "hzdzkjdx", "浙江"),
    u("浙江师范大学", "zhejiangshifandaxue", "zjsfdx", "浙江"),
    u("浙江金融职业学院", "zhejiangjinrongzhiyexueyuan", "zjjrzyxy", "浙江"),
    u("金华职业技术学院", "jinhuazhiyezhishuxueyuan", "jhzyjsxy", "浙江"),

    // ── 安徽 ──
    u("中国科学技术大学", "zhongguokexuejishudaxue", "zgkxjsdx", "安徽"),
    u("合肥工业大学", "hefeigongyedaxue", "hfgydx", "安徽"),
    u("安徽大学", "anhuidaxue", "ahdx", "安徽"),
    u("安徽职业技术学院", "anhuizhiyezhishuxueyuan", "ahzyjsxy", "安徽"),

    // ── 福建 ──
    u("厦门大学", "shamendaxue", "smdx", "福建"),
    u("福州大学", "fuzhoudaxue", "fzdx", "福建"),
    u("福建师范大学", "fujianshifandaxue", "fjsfdx", "福建"),
    u("华侨大学", "huaqiaodaxue", "hqdx", "福建"),
    u("福建船政交通职业学院", "fujianchuanzhengjiaotongzhiyexueyuan", "fjczjtzyxy", "福建"),

    // ── 江西 ──
    u("南昌大学", "nanchangdaxue", "ncdx", "江西"),
    u("江西师范大学", "jiangxishifandaxue", "jxsfdx", "江西"),
    u("江西财经大学", "jiangxicaijingdaxue", "jxcjdx", "江西"),
    u("九江职业技术学院", "jiujiangzhiyezhishuxueyuan", "jjzyjsxy", "江西"),

    // ── 山东 ──
    u("山东大学", "shandongdaxue", "sddx", "山东"),
    u("中国海洋大学", "zhongguohaiyangdaxue", "zghydx", "山东"),
    u("中国石油大学（华东）", "zhongguoshiyoudaxuehuadong", "zgsydxhd", "山东"),
    u("哈尔滨工业大学（威海）", "haerbingongyedaxueweihai", "hebgydxwh", "山东"),
    u("山东师范大学", "shandongshifandaxue", "sdsfdx", "山东"),
    u("青岛大学", "qingdaodaxue", "qddx", "山东"),
    u("山东商业职业技术学院", "shandongshangyezhiyezhishuxueyuan", "sdsyzyjsxy", "山东"),
    u("淄博职业学院", "zibozhiyexueyuan", "zbzyxy", "山东"),

    // ── 河南 ──
    u("郑州大学", "zhengzhoudaxue", "zzdx", "河南"),
    u("河南大学", "henandaxue", "hndx", "河南"),
    u("河南师范大学", "henanshifandaxue", "hnsfdx", "河南"),
    u("黄河水利职业技术学院", "huangheshuilizhiyezhishuxueyuan", "hhslzyjsxy", "河南"),

    // ── 湖北 ──
    u("武汉大学", "wuhandaxue", "whdx", "湖北"),
    u("华中科技大学", "huazhongkejidaxue", "hzkjdx", "湖北"),
    u("武汉理工大学", "wuhanligongdaxue", "whlgdx", "湖北"),
    u("华中师范大学", "huazhongshifandaxue", "hzsfdx", "湖北"),
    u("中南财经政法大学", "zhongnancaijingzhengfadaxue", "zncjzfdx", "湖北"),
    u("华中农业大学", "huazhongnongyedaxue", "hznydx", "湖北"),
    u("中国地质大学（武汉）", "zhongguodizhidaxuewuhan", "zgdzdxwh", "湖北"),
    u("武汉职业技术学院", "wuhanzhiyezhishuxueyuan", "whzyjsxy", "湖北"),

    // ── 湖南 ──
    u("中南大学", "zhongnandaxue", "zndx", "湖南"),
    u("湖南大学", "hunandaxue", "hndx", "湖南"),
    u("湖南师范大学", "hunanshifandaxue", "hnsfdx", "湖南"),
    u("国防科技大学", "guofangkejidaxue", "gfkjdx", "湖南"),
    u("湘潭大学", "xiangtandaxue", "xtdx", "湖南"),
    u("长沙民政职业技术学院", "changshaminzhengzhiyezhishuxueyuan", "csmzzyjsxy", "湖南"),

    // ── 广东 ──
    u("中山大学", "zhongshandaxue", "zsdx", "广东"),
    u("华南理工大学", "huananligongdaxue", "hnlgdx", "广东"),
    u("暨南大学", "jinandaxue", "jndx", "广东"),
    u("华南师范大学", "huananshifandaxue", "hnsfdx", "广东"),
    u("华南农业大学", "huanannongyedaxue", "hnnydx", "广东"),
    u("深圳大学", "shenzhendaxue", "szdx", "广东"),
    u("南方科技大学", "nanfangkejidaxue", "nfkjdx", "广东"),
    u("广东工业大学", "guangdonggongyedaxue", "gdgydx", "广东"),
    u("深圳职业技术学院", "shenzhenzhiyezhishuxueyuan", "szzyjsxy", "广东"),
    u("广州番禺职业技术学院", "guangzhoupanyuzhiyezhishuxueyuan", "gzpyzyjsxy", "广东"),

    // ── 广西 ──
    u("广西大学", "guangxidaxue", "gxdx", "广西"),
    u("广西师范大学", "guangxishifandaxue", "gxsfdx", "广西"),
    u("桂林电子科技大学", "guilindianzikejidaxue", "gldzkjdx", "广西"),
    u("南宁职业技术学院", "nanningzhiyezhishuxueyuan", "nnzyjsxy", "广西"),

    // ── 海南 ──
    u("海南大学", "hainandaxue", "hndx", "海南"),
    u("海南职业技术学院", "hainanzhiyezhishuxueyuan", "hnzyjsxy", "海南"),

    // ── 重庆 ──
    u("重庆大学", "zhongqingdaxue", "cqdx", "重庆"),
    u("西南大学", "xinandaxue", "xndx", "重庆"),
    u("西南政法大学", "xinanzhengfadaxue", "xnzfdx", "重庆"),
    u("重庆邮电大学", "zhongqingyoudiandaxue", "cqyddx", "重庆"),
    u("重庆工商大学", "zhongqinggongshangdaxue", "cqgsdx", "重庆"),
    u("重庆交通大学", "zhongqingjiaotongdaxue", "cqjtdx", "重庆"),
    u("重庆师范大学", "zhongqingshifandaxue", "cqsfdx", "重庆"),
    u("重庆电子工程职业学院", "zhongqingdianzigongchengzhiyexueyuan", "cqdzgczyxy", "重庆"),
    u("重庆工业职业技术学院", "zhongqinggongyezhiyezhishuxueyuan", "cqgyzyjsxy", "重庆"),

    // ── 四川 ──
    u("四川大学", "sichuandaxue", "scdx", "四川"),
    u("电子科技大学", "dianzikejidaxue", "dzkjdx", "四川"),
    u("西南交通大学", "xinanjiaotongdaxue", "xnjtdx", "四川"),
    u("西南财经大学", "xinancaijingdaxue", "xncjdx", "四川"),
    u("四川农业大学", "sichuannongyedaxue", "scnydx", "四川"),
    u("成都理工大学", "chengduligongdaxue", "cdlgdx", "四川"),
    u("成都纺织高等专科学校", "chengdufangzhigaodengzhuankexuexiao", "cdfzgdzkxx", "四川"),
    u("四川工程职业技术学院", "sichuangongchengzhiyezhishuxueyuan", "scgczyjsxy", "四川"),

    // ── 贵州 ──
    u("贵州大学", "guizhoudaxue", "gzdx", "贵州"),
    u("贵州师范大学", "guizhoushifandaxue", "gzsfdx", "贵州"),
    u("贵州交通职业技术学院", "guizhoujiaotongzhiyezhishuxueyuan", "gzjtzyjsxy", "贵州"),

    // ── 云南 ──
    u("云南大学", "yunnandaxue", "yndx", "云南"),
    u("昆明理工大学", "kunmingligongdaxue", "kmlgdx", "云南"),
    u("云南师范大学", "yunnanshifandaxue", "ynsfdx", "云南"),
    u("昆明冶金高等专科学校", "kunmingyejingaodengzhuankexuexiao", "kmyjgdzkxx", "云南"),

    // ── 西藏 ──
    u("西藏大学", "xizangdaxue", "xzdx", "西藏"),
    u("西藏职业技术学院", "xizangzhiyezhishuxueyuan", "xzzyjsxy", "西藏"),

    // ── 陕西 ──
    u("西安交通大学", "xianjiaotongdaxue", "xajtdx", "陕西"),
    u("西北工业大学", "xibeigongyedaxue", "xbgydx", "陕西"),
    u("西北农林科技大学", "xibeinonglinkejidaxue", "xbnlkjdx", "陕西"),
    u("西安电子科技大学", "xiandianzikejidaxue", "xadzkjdx", "陕西"),
    u("陕西师范大学", "shanxishifandaxue", "sxsfdx", "陕西"),
    u("长安大学", "changandaxue", "cadx", "陕西"),
    u("西北大学", "xibeidaxue", "xbdx", "陕西"),
    u("杨凌职业技术学院", "yanglingzhiyezhishuxueyuan", "ylzyjsxy", "陕西"),

    // ── 甘肃 ──
    u("兰州大学", "lanzhoudaxue", "lzdx", "甘肃"),
    u("西北师范大学", "xibeishifandaxue", "xbsfdx", "甘肃"),
    u("兰州石化职业技术学院", "lanzhoushihuazhiyezhishuxueyuan", "lzshzyjsxy", "甘肃"),

    // ── 青海 ──
    u("青海大学", "qinghaidaxue", "qhdx", "青海"),
    u("青海畜牧兽医职业技术学院", "qinghaixumushouyizhiyezhishuxueyuan", "qhxmsyzyjsxy", "青海"),

    // ── 宁夏 ──
    u("宁夏大学", "ningxiadaxue", "nxdx", "宁夏"),
    u("宁夏职业技术学院", "ningxiazhiyezhishuxueyuan", "nxzyjsxy", "宁夏"),

    // ── 新疆 ──
    u("新疆大学", "xinjiangdaxue", "xjdx", "新疆"),
    u("石河子大学", "shihezidaxue", "shzdx", "新疆"),
    u("新疆农业职业技术学院", "xinjiangnongyezhiyezhishuxueyuan", "xjnyzyjsxy", "新疆"),
)

/** 搜索入口：按 query 过滤（空 query 返回全部），命中 名称/拼音/简写。 */
fun searchUniversities(query: String): List<University> =
    ALL_UNIVERSITIES.filter { it.matches(query) }
