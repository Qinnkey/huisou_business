package com.huisou.service.impl;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.huisou.cache.RedisUserTokenCache;
import com.huisou.constant.ContextConstant;
import com.huisou.po.UserPo;
import com.huisou.po.VisitRecordPo;
import com.huisou.service.UserAuthService;
import com.huisou.service.UserService;
import com.huisou.service.VisitRecordService;
import com.common.MD5Util;

@Service
public class UserAuthServiceImpl implements UserAuthService {
	
	private  static  final Logger logger = LoggerFactory.getLogger(UserAuthServiceImpl.class);
	@Value(value="${wechat.mp.appId}")
	private String appId;
	@Value(value="${wechat.mp.secret}")
	private String secret;
	
	@Value(value="${state.url}")
	private String stateUrl;
	
	@Autowired
	private RestTemplate restTemplate;
	@Autowired
	private RedisUserTokenCache userTokenCache;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private VisitRecordService visitSer;
	
	@Override
	public void authCode(String state,HttpServletResponse response){
		HashMap<String, String> codeReqMap = new HashMap<String,String>();
		codeReqMap.put("appId", appId);
		codeReqMap.put("redirectUri",URLDecoder.decode("http://sxy.huisou.cn/userAuth/getAuthOpenId"));
		codeReqMap.put("scope","snsapi_userinfo");
		codeReqMap.put("state","hssxy");
		
		String redirectUri = URLDecoder.decode("http://sxy.huisou.cn/userAuth/getAuthOpenId");
		String codeUrl = "https://open.weixin.qq.com/connect/oauth2/authorize?appid="+appId+"&redirect_uri="
				+ redirectUri +"&response_type=code&scope=snsapi_userinfo&state="+state+"#wechat_redirect";
//		String res = restTemplate.getForObject(codeUrl, String.class, codeReqMap);
		try {
			response.sendRedirect(codeUrl);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
//		https://open.weixin.qq.com/connect/oauth2/authorize?appid=wxc004032102b0d269&redirect_uri=http://sxy.huisou.cn/userAuth/getAuthOpenId&response_type=code&scope=snsapi_userinfo&state=hssxy#wechat_redirect
	}

	@Override
	public void getAuthOpenId(String code, String state, HttpServletResponse response) {
		// TODO Auto-generated method stub
		logger.info("微信授权认证回调===code="+code+";state="+state);
		HashMap<String, String> codeReqMap = new HashMap<String,String>();
		codeReqMap.put("appId", appId);
		codeReqMap.put("secret",secret);
		codeReqMap.put("code",code);
		
		String userToken = "";
		String codeUrl = "https://api.weixin.qq.com/sns/oauth2/access_token?appid={appId}&secret={secret}&code={code}&grant_type=authorization_code";
		String result = restTemplate.getForObject(codeUrl, String.class, codeReqMap);
		logger.info("获取access_token接口返回===result"+result);
		if (result != null) {
			JSONObject authJson = JSON.parseObject(result);
			if (authJson != null) {
				String accessToken = authJson.getString("access_token");
				String openId = authJson.getString("openid");
				logger.info("获取access_token接口返回不为空，获取access_token与openid="+accessToken+";"+openId);
				
				//记录登录
				VisitRecordPo visitRecordVo = new VisitRecordPo();
				visitRecordVo.setVisitType(ContextConstant.VISIT_RECORD_TYPE_DL);
				visitSer.insertOne(visitRecordVo);
				//获取微信用户信息
				userToken = getUserinfo(accessToken,openId);
			}
		}
		//跳转项目连接，链接传userToken参数
		try {
			response.sendRedirect(stateUrl+userToken+"&state="+state);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	@Override
	public String getUserinfo(String accessToken, String openId) {
		// TODO Auto-generated method stub
		HashMap<String, String> codeReqMap = new HashMap<String,String>();
		codeReqMap.put("accessToken",accessToken);
		codeReqMap.put("openId",openId);
		//获取到openId生成user_token,user_token=MD5(MD5(openId))
		String userToken = MD5Util.md5Encode(MD5Util.md5Encode(openId));
		
		//查询数据库，如果openid用户存在则直接返回，如果不存在获取用户信息
		Map para = new HashMap();
		para.put("openid", openId);
		UserPo userOne = userService.find(para);
		if(null!=userOne){
			logger.info("已经获取用户信息，不调用微信接口，放入缓存");
			//放入redis缓存
			userTokenCache.addUserToken(userToken, userOne);
		}else{
			String codeUrl = "https://api.weixin.qq.com/sns/userinfo?access_token={accessToken}&openid={openId}&lang=zh_CN";
			String result = restTemplate.getForObject(codeUrl, String.class, codeReqMap);
			if (result != null) {
				JSONObject jsonObject = JSON.parseObject(result);
				//保存用户信息
				if (jsonObject != null) {
					logger.info("调用微信userinfo接口获取用户信息，放入缓存"+jsonObject);    
					UserPo userPo= new UserPo();
			    	userPo.setCity(jsonObject.getString("city"));
			    	userPo.setProvince(jsonObject.getString("province"));
			    	userPo.setHeadimgurl(jsonObject.getString("headimgurl"));
			    	userPo.setNickname(jsonObject.getString("nickname"));
			    	userPo.setCreateTime(new Date());
			    	userPo.setOpenid(jsonObject.getString("openid"));
			    	userPo.setCountry(jsonObject.getString("country"));
			    	userPo.setSex(jsonObject.getString("sex"));
			    	userService.addOne(userPo);
			    	//放入redis缓存
					userTokenCache.addUserToken(userToken, userPo);
				}
			}
		}
		
		return userToken;
	}
}