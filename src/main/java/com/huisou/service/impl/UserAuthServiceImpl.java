package com.huisou.service.impl;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
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
import com.huisou.po.AgentPo;
import com.huisou.po.UserAccountPo;
import com.huisou.po.UserPo;
import com.huisou.po.VisitRecordPo;
import com.huisou.service.AgentService;
import com.huisou.service.UserAccountService;
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
	
	@Value(value="${wx.redirectUri}")
	private String wxRedirectUri;
	@Autowired
	private RestTemplate restTemplate;
	@Autowired
	private RedisUserTokenCache userTokenCache;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private VisitRecordService visitSer;
	
	@Autowired
	private AgentService agentSer;
	
	@Autowired
	private UserAccountService userAccountService;
	
	@Override
	public void authCode(String state,HttpServletResponse response){
		logger.info("authCode获取地址打印stateUrl=="+stateUrl+";wxRedirectUri=="+wxRedirectUri);
		HashMap<String, String> codeReqMap = new HashMap<String,String>();
		codeReqMap.put("appId", appId);
		codeReqMap.put("redirectUri",URLDecoder.decode(wxRedirectUri));
		codeReqMap.put("scope","snsapi_userinfo");
		codeReqMap.put("state","hssxy");
		
		String redirectUri = URLDecoder.decode(wxRedirectUri);
		logger.info("微信===redirectUri="+redirectUri);
		
		String codeUrl = "https://open.weixin.qq.com/connect/oauth2/authorize?appid="+appId+"&redirect_uri="
				+ redirectUri +"&response_type=code&scope=snsapi_userinfo&state="+state+"#wechat_redirect";
		
		logger.info("微信authCode===codeUrl="+codeUrl);
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
	public void getAuthOpenId(String code, String state, HttpServletRequest request, HttpServletResponse response) {
		// TODO Auto-generated method stub
		logger.info("获取用户openid，微信授权认证回调===code="+code+";state="+state);
		String shareOpenId = "";
		if(state.contains(";")){
			logger.info("state为跳转页面和openid拼接");
			String[] strs = state.split(";");
			state = strs[0];
			shareOpenId = strs[1];
		}
		HashMap<String, String> codeReqMap = new HashMap<String,String>();
		codeReqMap.put("appId", appId);
		codeReqMap.put("secret",secret);
		codeReqMap.put("code",code);
		
		String userToken = "";
		
		userToken = String.valueOf(null!=request.getSession().getAttribute("userToken")?request.getSession().getAttribute("userToken"):"");
		
		logger.info("----userToken is null----"+userToken+";"+(StringUtils.isEmpty(userToken)||"null".equals(userToken)));
		
//		if(StringUtils.isEmpty(userToken)||"null".equals(userToken)){
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
					visitRecordVo.setCreateTime(new Date());
					visitRecordVo.setVisitType(ContextConstant.VISIT_RECORD_TYPE_DL);
					UserPo userPo = userService.getUserByOpenId(openId);
					if (null != userPo){
						visitRecordVo.setUserId(userPo.getUserId());
					}
					visitSer.insertOne(visitRecordVo);
					//获取微信用户信息
					userToken = getUserinfo(accessToken,openId,shareOpenId);
					
					request.getSession().setAttribute("userToken", userToken);
					
					logger.info("----放入session--userToken--"+userToken);
					
				}
			}
//		}
		
		logger.info("----userToken执行完成----"+userToken);
		//跳转项目连接，链接传userToken参数
		try {
			logger.info("----跳转userToken连接----");
			response.sendRedirect(stateUrl+userToken+"&state="+state);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	@Override
	public String getUserinfo(String accessToken, String openId, String shareOpenId) {
		// TODO Auto-generated method stub
		logger.info("getUserinfo==========shareOpenId=="+shareOpenId);
		if(StringUtils.isBlank(accessToken)&&StringUtils.isBlank(openId)){
			return null;
		}
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
			UserPo shareUser = null;
			//如果分享人不为空，并且不是自己，则设置分享人
	    	if(StringUtils.isNotBlank(shareOpenId)&&!"null".equals(shareOpenId)&&!openId.equals(shareOpenId)&&ContextConstant.AGENT_USER.equals(userOne.getIsAgency())){
	    		logger.info("设置我的分享人====="+shareOpenId);
	    		shareUser = userService.getUserByOpenId(shareOpenId);
	    		
	    		String curClassId = String.valueOf(userOne.getClassmateUserId());
	    		if(("null".equals(curClassId)||StringUtils.isEmpty(curClassId))&&"3".equals(shareUser.getIsAgency())){
	    			userOne.setClassmateUserId(shareUser.getUserId());
		    		synchronized(userOne.getOpenid().intern()){
		    			userService.updateOne(userOne);
			    	}
	    		}
	    		
	    		String oneClassId = String.valueOf(userOne.getClassmateUserId());
		    	logger.info("oneClassId==============="+oneClassId);
		    	if(!("null".equals(oneClassId)||StringUtils.isEmpty(oneClassId))&&"3".equals(shareUser.getIsAgency())){
		    		logger.info("员工和空用户建立关系userOne=="+userOne.getUserId()+"分享人id=="+shareUser.getUserId());
		    		AgentPo po = new AgentPo();
		    		po.setUserId(userOne.getUserId());
		    		po.setInitialAgentUserId(shareUser.getUserId());
		    		po.setAgentUserId(shareUser.getUserId());
		    		po.setClassmateUserId(shareUser.getUserId());
		    		po.setClassEmpId(shareUser.getUserId());
		    		agentSer.insertAgent(po);
		    	}
	    	}
	    	
	    	
			logger.info("已经获取用户信息，不调用微信接口，放入缓存,userToken=="+userToken);
			//放入redis缓存
			userTokenCache.addUserToken(userToken, userOne);
			
			return userToken;
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
			    	
			    	UserPo shareUser = null;
			    	if(StringUtils.isNotBlank(shareOpenId)&&!"null".equals(shareOpenId)&&!openId.equals(shareOpenId)){
			    		logger.info("设置我的分享人====="+shareOpenId);
			    		shareUser = userService.getUserByOpenId(shareOpenId);
				    	userPo.setClassmateUserId(shareUser.getUserId());
				    	
			    	}
			    	userService.addOne(userPo);
			    	//新用户信息保存到user_account
			    	UserAccountPo userAccountPo = new UserAccountPo();
			    	userAccountPo.setUserId(userPo.getUserId());
			    	userAccountPo.setCreateTime(new Date());
			    	userAccountService.insertOne(userAccountPo);
			    	
			    	shareUserAgent(userPo.getUserId(), shareUser);
			    	
			    	//放入redis缓存
					userTokenCache.addUserToken(userToken, userPo);
				}
			}
		}
		logger.info("userToken---------------------"+userToken);
		return userToken;
	}

	@Override
	public void shareUserAgent(Integer userId, UserPo shareUser) {
		//判断分享人，如果分享人不为空，并且分享人和插入人userid不一样，则插入关系
		if(null!=shareUser&&!String.valueOf(userId).equals(shareUser.getUserId())){
			logger.info("判断分享人，如果分享人不为空，并且分享人和插入人userid不一样，则插入关系");
			AgentPo shareAgent = agentSer.queryByUserId(shareUser.getUserId());
			//如果分享人是公司员工
			if(ContextConstant.AGENT_EMP.equals(shareUser.getIsAgency())){
				logger.info("分享人是员工===");
				AgentPo po = new AgentPo();
				po.setUserId(userId);
				po.setInitialAgentUserId(shareUser.getUserId());
				po.setAgentUserId(shareUser.getUserId());
				po.setClassmateUserId(shareUser.getUserId());
				po.setClassEmpId(shareUser.getUserId());
				agentSer.insertAgent(po);
			}
			else if(ContextConstant.AGENT_PROXY.equals(shareUser.getIsAgency())){
				AgentPo po = new AgentPo();
				po.setUserId(userId);
				po.setInitialAgentUserId(shareUser.getUserId());
				po.setAgentUserId(shareUser.getUserId());
				po.setClassmateUserId(shareUser.getUserId());
				if(null!=shareAgent){
					logger.info("分享人是代理人，shareAgent不为null===");
					po.setClassEmpId(shareAgent.getClassEmpId());
				}
				agentSer.insertAgent(po);
			}
			else{
				AgentPo po = new AgentPo();
				po.setUserId(userId);
				logger.info("分享人是普通用户===");
				
				
				if(null!=shareUser.getMemberSetId()&&shareUser.getMemberSetId()>0){
					po.setAgentUserId(shareUser.getUserId());
				}
				
				if(null!=shareAgent){
					if(null==shareUser.getMemberSetId()){
						po.setAgentUserId(shareAgent.getAgentUserId());
					}
		    		po.setInitialAgentUserId(shareAgent.getInitialAgentUserId());
		    		po.setClassmateUserId(shareUser.getUserId());
		    		po.setClassEmpId(shareAgent.getClassEmpId());
				}else{
					po.setInitialAgentUserId(shareUser.getUserId());
		    		po.setAgentUserId(shareUser.getUserId());
		    		po.setClassmateUserId(shareUser.getUserId());
				}
				agentSer.insertAgent(po);
			}
		}
	}
}
