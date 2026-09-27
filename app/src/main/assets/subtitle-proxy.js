(function () {
    'use strict';
    if (window.__lampaSubtitleProxy) return;
    var generation=0, active=null, pending=null, entering=0, directUrl='';
    function query(payload, success, failure) {
        window.cefriumQuery({request:JSON.stringify(payload),onSuccess:success||function(){},
            onFailure:failure||function(){console.warn('[LAMPA proxy] bridge unavailable');}});
    }
    function stop() {
        generation++;directUrl='';
        if(pending){clearTimeout(pending.timer);pending=null;}
        query({type:'proxy-stop',sourceId:active?active.sourceId:-1});
        active=null;
    }
    function eligible(raw) {
        try {
            var u=new URL(raw);
            return /^https?:$/.test(u.protocol)&&!/\.(m3u8|mpd)$/i.test(u.pathname)&&
                !u.searchParams.has('preload')&&!u.searchParams.has('stat')&&u.searchParams.has('link')&&u.searchParams.has('play')&&
                (u.pathname.indexOf('/stream')===0||u.searchParams.has('index'));
        }catch(_){return false;}
    }
    function prepare(src,ready,direct) {
        var ticket=++generation,settled=false;
        if(pending)clearTimeout(pending.timer);
        function failure() {
            if(settled||ticket!==generation)return;
            settled=true;clearTimeout(pending&&pending.timer);pending=null;
            active=null;query({type:'proxy-stop',sourceId:-1});
            if(Lampa.Select&&Lampa.Select.show)Lampa.Select.show({
                title:'Встроенные субтитры недоступны',
                items:[{title:'Продолжить без встроенных субтитров',direct:true},{title:'Отмена'}],
                onSelect:function(item){
                    if(ticket!==generation)return;
                    if(Lampa.Select.close)Lampa.Select.close();
                    stop();
                    if(item.direct){directUrl=src;direct();}
                }
            });
        }
        pending={timer:setTimeout(failure,15000)};
        query({type:'proxy-register',url:String(src),requestId:ticket},function(raw){
            var result;try{result=JSON.parse(raw);}catch(_){failure();return;}
            if(settled||ticket!==generation)return;
            if(!result||result.requestId!==ticket||result.originalUrl!==src||!/^http:\/\/127\.0\.0\.1:\d+\//.test(result.playbackUrl)){failure();return;}
            settled=true;clearTimeout(pending&&pending.timer);pending=null;directUrl='';active=result;ready();
        },failure);
    }
    function hook() {
        var p=window.Lampa&&Lampa.PlayerVideo;
        if(!p||!p.url)return;
        if(!p.__lampaProxyBound){
            p.__lampaProxyBound=true;
            var load=p.url,destroy=p.destroy;
            p.url=function(src){
                var self=this,args=Array.prototype.slice.call(arguments);
                if(active&&active.originalUrl===src&&!pending){args[0]=active.playbackUrl;return load.apply(self,args);}
                if(src===directUrl)return load.apply(self,args);
                if(!eligible(src)){stop();return load.apply(self,args);}
                prepare(src,function(){args[0]=active.playbackUrl;load.apply(self,args);},function(){load.apply(self,args);});
            };
            if(destroy)p.destroy=function(){if(!entering)stop();return destroy.apply(this,arguments);};
        }
        var player=Lampa.Player;
        if(player&&player.play&&!player.__lampaProxyPlayBound){
            player.__lampaProxyPlayBound=true;
            var play=player.play;
            player.play=function(data){
                var self=this,args=arguments,src=data&&data.url;
                if(typeof src==='string'&&Lampa.Torserver&&Lampa.Torserver.toPlayUrl)src=Lampa.Torserver.toPlayUrl(src);
                if(!eligible(src)){stop();return play.apply(self,args);}
                function start(){entering++;try{return play.apply(self,args);}finally{entering--;}}
                prepare(src,start,start);
            };
        }
    }
    window.__lampaSubtitleProxy={
        originalUrl:function(url){return active&&active.playbackUrl===url?active.originalUrl:url;},
        stop:stop
    };
    // A new JS global identifies a real document, unlike subframe loading callbacks.
    query({type:'proxy-page'});
    setInterval(hook,500);hook();
})();
